package de.code_freak.codefreak.service

import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.DockerClient.ListContainersParam.withLabel
import com.spotify.docker.client.DockerClient.ListContainersParam.withStatusRunning
import com.spotify.docker.client.exceptions.ImageNotFoundException
import com.spotify.docker.client.messages.Container
import com.spotify.docker.client.messages.HostConfig
import de.code_freak.codefreak.config.AppConfiguration
import de.code_freak.codefreak.entity.Answer
import de.code_freak.codefreak.entity.AssignmentStatus
import de.code_freak.codefreak.repository.AnswerRepository
import de.code_freak.codefreak.service.file.FileService
import de.code_freak.codefreak.util.withTrailingSlash
import org.glassfish.jersey.internal.LocalizationMessages
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.StreamUtils
import java.io.InputStream
import java.util.ArrayList
import java.util.UUID
import java.util.regex.Pattern
import javax.ws.rs.ProcessingException

@Service
class ContainerService : BaseService() {

  companion object {
    private const val LABEL_PREFIX = "de.code-freak."
    const val LABEL_READ_ONLY_ANSWER_ID = LABEL_PREFIX + "answer-id-read-only"
    const val LABEL_ANSWER_ID = LABEL_PREFIX + "answer-id"
    const val LABEL_INSTANCE_ID = LABEL_PREFIX + "instance-id"
    const val PROJECT_PATH = "/home/coder/project"
  }

  private val log = LoggerFactory.getLogger(this::class.java)
  private var idleContainers: Map<String, Long> = mapOf()

  @Autowired
  lateinit var docker: DockerClient

  @Autowired
  lateinit var config: AppConfiguration

  @Autowired
  private lateinit var answerRepository: AnswerRepository

  @Autowired
  private lateinit var containerService: ContainerService

  @Autowired
  private lateinit var fileService: FileService

  @Autowired
  private lateinit var answerService: AnswerService

  @EventListener(ContextRefreshedEvent::class)
  fun init() {
    pullDockerImages(listOf(config.ide.image, config.evaluation.codeclimate.image))
  }

  /**
   * Inherit behaviour of the standard Docker CLI and fallback to :latest if no tag is given
   */
  fun normalizeImageName(imageName: String) =
      if (imageName.contains(':')) {
        imageName
      } else {
        "$imageName:latest"
      }

  fun pullDockerImages(images: List<String>) {
    for (image in images.map(this::normalizeImageName)) {
      val imageInfo = try {
        docker.inspectImage(image)
      } catch (e: ImageNotFoundException) {
        null
      }

      val pullRequired = config.docker.pullPolicy == "always" || (config.docker.pullPolicy == "if-not-present" && imageInfo == null)
      if (!pullRequired) {
        if (imageInfo == null) {
          log.warn("Image pulling is disabled but $image is not available on the daemon!")
        } else {
          log.debug("Image present: $image ${imageInfo.id()}")
        }
        continue
      }

      log.info("Pulling latest image for: $image")
      docker.pull(image)
      log.info("Updated docker image $image to ${docker.inspectImage(image).id()}")
    }
  }

  /**
   * Start an IDE container for the given submission and returns the container ID
   * If there is already a container for the submission it will be used instead
   */
  @Synchronized
  @Throws(ResourceLimitException::class)
  fun startIdeContainer(answer: Answer, readOnly: Boolean = false) {
    // either take existing container or create a new one
    var containerId = this.getIdeContainer(answer.id, readOnly)
    if (containerId != null && isContainerRunning(containerId)) {
      return
    }

    if (!canStartNewIdeContainer()) {
      throw ResourceLimitException("Cannot start new IDE. Maximum capacity reached.")
    }

    if (containerId == null) {
      containerId = this.createIdeContainer(answer, readOnly)
      docker.startContainer(containerId)
      // prepare the environment after the container has started
      this.copyFilesToIde(containerId, answer.id)
    } else {
      // make sure the container is running. Also existing ones could have been stopped
      docker.startContainer(containerId)
    }
  }

  fun canStartNewIdeContainer(): Boolean {
    if (config.ide.maxContainers < 0) return true
    var totalIdeContainers = getContainersWithLabel(LABEL_ANSWER_ID).size
    totalIdeContainers += getContainersWithLabel(LABEL_READ_ONLY_ANSWER_ID).size
    return totalIdeContainers < config.ide.maxContainers
  }

  /**
   * Run a command as root inside container and return the result as string
   */
  fun exec(containerId: String, cmd: Array<String>): ExecResult {
    val exec = docker.execCreate(
        containerId, cmd,
        DockerClient.ExecCreateParam.attachStdin(), // this is not needed but a workaround for spotify/docker-client#513
        DockerClient.ExecCreateParam.attachStdout(),
        DockerClient.ExecCreateParam.attachStderr(),
        DockerClient.ExecCreateParam.user("root")
    )
    return ExecResult(output = docker.execStart(exec.id()).readFully(),
        exitCode = docker.execInspect(exec.id()).exitCode() ?: -1)
  }

  /**
   * Get the URL for an IDE container
   * TODO: make this configurable for different types of hosting/reverse proxies/etc
   */
  fun getIdeUrl(answerId: UUID, readOnly: Boolean = false): String {
    return config.traefik.url + getIdePath(answerId, readOnly)
  }

  protected fun getIdePath(answerId: UUID, readOnly: Boolean): String {
    return if (readOnly) "/reader/$answerId/" else "/ide/$answerId/"
  }

  fun isIdeContainerRunning(answerId: UUID): Boolean {
    return getIdeContainer(answerId)?.let { isContainerRunning(it) } ?: false
  }

  protected fun getIdeContainer(answerId: UUID, readOnly: Boolean = false): String? {
    val label = if (readOnly) LABEL_READ_ONLY_ANSWER_ID else LABEL_ANSWER_ID
    return getContainerWithLabel(label, answerId.toString())?.id()
  }

  protected fun getContainerWithLabel(label: String, value: String? = null): Container? {
    return getContainersWithLabel(label, value).firstOrNull()
  }

  protected fun getContainersWithLabel(label: String, value: String? = null) = listContainers(withLabel(label, value))

  protected fun listContainers(vararg listContainerParams: DockerClient.ListContainersParam): List<Container> {
    return docker.listContainers(withLabel(LABEL_INSTANCE_ID, config.instanceId), *listContainerParams)
  }

  @Transactional
  fun saveAnswerFiles(answer: Answer, force: Boolean = false): Answer {
    if (!force && answer.task.assignment?.status != AssignmentStatus.OPEN) {
      log.info("Skipped saving of files from answer ${answer.id} because assignment is not open")
      return answer
    }
    val containerId = getIdeContainer(answer.id) ?: return answer
    archiveContainer(containerId, "$PROJECT_PATH/.") { tar ->
      fileService.writeCollectionTar(answer.id).use { StreamUtils.copy(tar, it) }
    }
    log.info("Saved files of answer ${answer.id} from container $containerId (force=$force)")
    return entityManager.merge(answer)
  }

  protected fun archiveContainer(containerId: String, path: String, process: (InputStream) -> Unit) {
    try {
      docker.archiveContainer(containerId, path).use(process)
    } catch (e: ProcessingException) {
      // okay until this is fixed https://github.com/eclipse-ee4j/jersey/issues/3486
      if (e.message != LocalizationMessages.MESSAGE_CONTENT_INPUT_STREAM_CLOSE_FAILED()) {
        throw e
      }
    }
  }

  protected fun createContainer(
    image: String,
    configure: ContainerBuilder.() -> Unit = {}
  ): String {

    val builder = ContainerBuilder()
    builder.configure()

    builder.containerConfig {
      image(normalizeImageName(image))
    }
    builder.labels += mapOf(
        LABEL_INSTANCE_ID to config.instanceId
    )

    return docker.createContainer(builder.build(), builder.name).id()!!
  }

  /**
   * Configure and create a new IDE container.
   * Returns the ID of the created container
   */
  protected fun createIdeContainer(answer: Answer, readOnly: Boolean = false): String {
    val answerId = answer.id.toString()
    val answerLabel = if (readOnly) LABEL_READ_ONLY_ANSWER_ID else LABEL_ANSWER_ID

    val containerId = createContainer(config.ide.image) {
      labels = mapOf(
          answerLabel to answerId,
          "traefik.enable" to "true",
          "traefik.frontend.rule" to "PathPrefixStrip: " + getIdePath(answer.id, readOnly),
          "traefik.port" to "3000",
          "traefik.frontend.headers.customResponseHeaders" to "Access-Control-Allow-Origin:*"
      )
      hostConfig {
        restartPolicy(HostConfig.RestartPolicy.unlessStopped())
        capAdd("SYS_PTRACE") // required for lsof
        memory(config.ide.memory)
        memorySwap(config.ide.memory) // memory+swap = memory ==> 0 swap
        nanoCpus(config.ide.cpus * 1000000000L)
      }
    }

    // attach to network
    docker.connectToNetwork(containerId, config.ide.network)

    return containerId
  }

  /**
   * Prepare a running container with files and other commands like chmod, etc.
   */
  protected fun copyFilesToIde(containerId: String, answerId: UUID) {
    // extract possible existing files of the current submission into project dir
    if (fileService.collectionExists(answerId)) {
      fileService.readCollectionTar(answerId).use { docker.copyToContainer(it, containerId, PROJECT_PATH) }
    }

    // change owner from root to coder so we can edit our project files
    exec(containerId, arrayOf("chown", "-R", "coder:coder", PROJECT_PATH))
  }

  fun answerFilesUpdated(answerId: UUID) {
    getIdeContainer(answerId)?.let {
      // use sh to make globbing work
      // two globs: one for regular files and one for hidden files/dirs except . and ..
      exec(it, arrayOf("sh", "-c", "rm -rf $PROJECT_PATH/* $PROJECT_PATH/.[!.]*"))
      copyFilesToIde(it, answerId)
    }
  }

  protected fun isContainerRunning(containerId: String): Boolean = docker.inspectContainer(containerId).state().running()

  @Scheduled(
      fixedRateString = "\${code-freak.ide.idle-check-rate}",
      initialDelayString = "\${code-freak.ide.idle-check-rate}"
  )
  protected fun shutdownIdleIdeContainers() {
    log.debug("Checking for idle containers")
    // create a new map to not leak memory if containers disappear in another way
    val newIdleContainers: MutableMap<String, Long> = mutableMapOf()
    val containers = mutableListOf<Container>().apply {
      addAll(listContainers(withLabel(LABEL_ANSWER_ID), withStatusRunning()))
      addAll(listContainers(withLabel(LABEL_READ_ONLY_ANSWER_ID), withStatusRunning()))
    }
    containers.forEach {
      val containerId = it.id()
      // TODO: Use `cat /proc/net/tcp` instead of lsof (requires no privileges)
      val connections = exec(containerId, arrayOf("/opt/code-freak/num-active-connections.sh")).output.trim()
      if (connections == "0") {
        val now: Long = System.currentTimeMillis()
        val idleSince: Long = idleContainers[containerId] ?: now
        val idleFor = now - idleSince
        log.debug("Container $containerId has been idle for more than $idleFor ms")
        if (idleFor >= config.ide.idleShutdownThreshold) {
          val labels = it.labels()!!
          val answerId = labels[LABEL_ANSWER_ID] ?: labels[LABEL_READ_ONLY_ANSWER_ID]
          if (labels.containsKey(LABEL_READ_ONLY_ANSWER_ID)) {
            log.info("Shutting down read container $containerId for answer $answerId")
          } else {
            val answer = answerRepository.findById(UUID.fromString(answerId))
            if (answer.isPresent) {
              containerService.saveAnswerFiles(answer.get())
            } else {
              log.warn("Answer $answerId not found. Files are not saved!")
            }
            log.info("Shutting down container $containerId of answer $answerId")
          }
          docker.stopContainer(containerId, 5)
        } else {
          newIdleContainers[containerId] = idleSince
        }
      }
    }
    idleContainers = newIdleContainers
  }

  fun runCodeclimate(answer: Answer): String {
    val containerId = createContainer(config.evaluation.codeclimate.image) {
      name = "codeclimate_orchestrator_${answer.id}"
      doNothingAndKeepAlive()
      hostConfig {
        appendBinds("/var/run/docker.sock:/var/run/docker.sock", "/tmp/cc:/tmp/cc")
      }
      containerConfig {
        env("CODECLIMATE_ORCHESTRATOR=$name", "CODECLIMATE_CODE=/code")
      }
    }
    answerService.copyFilesForEvaluation(answer).use { docker.copyToContainer(it, containerId, "/code") }
    docker.startContainer(containerId)
    // `analyze` would also install missing engines but may time out in the process. Also `engines:install` will update images.
    exec(containerId, arrayOf("/usr/src/app/bin/codeclimate", "engines:install"))
    val output = exec(containerId, arrayOf("/usr/src/app/bin/codeclimate", "analyze", "-f", "json")).output
    docker.killContainer(containerId)
    docker.removeContainer(containerId)
    return output
  }

  fun runCommandsForEvaluation(
    answer: Answer,
    image: String,
    projectPath: String,
    commands: List<String>,
    stopOnFail: Boolean,
    processFiles: ((InputStream) -> Unit)? = null
  ): List<ExecResult> {
    pullDockerImages(listOf(image))
    val containerId = createContainer(image) {
      doNothingAndKeepAlive()
      containerConfig { workingDir(projectPath) }
    }
    answerService.copyFilesForEvaluation(answer).use { docker.copyToContainer(it, containerId, projectPath) }
    docker.startContainer(containerId)
    val outputs = mutableListOf<ExecResult>()
    commands.forEach {
      if (stopOnFail && outputs.size > 0 && outputs.last().exitCode != 0L) {
        outputs.add(ExecResult("", -1))
      } else {
        outputs.add(exec(containerId, splitCommand(it)))
      }
    }
    if (processFiles !== null) {
      archiveContainer(containerId, "${projectPath.withTrailingSlash()}.", processFiles)
    }
    docker.killContainer(containerId)
    docker.removeContainer(containerId)
    return outputs
  }

  private fun splitCommand(command: String): Array<String> {
    // from https://stackoverflow.com/a/366532/5519485
    val matchList = ArrayList<String>()
    val regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'")
    val regexMatcher = regex.matcher(command)
    while (regexMatcher.find()) {
      when {
        regexMatcher.group(1) != null -> // Add double-quoted string without the quotes
          matchList.add(regexMatcher.group(1))
        regexMatcher.group(2) != null -> // Add single-quoted string without the quotes
          matchList.add(regexMatcher.group(2))
        else -> // Add unquoted word
          matchList.add(regexMatcher.group())
      }
    }
    return matchList.toArray(arrayOf())
  }
}
