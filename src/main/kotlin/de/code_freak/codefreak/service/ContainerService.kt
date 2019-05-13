package de.code_freak.codefreak.service

import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.messages.ContainerConfig
import com.spotify.docker.client.messages.HostConfig
import de.code_freak.codefreak.entity.Answer
import de.code_freak.codefreak.repository.AnswerRepository
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.UUID
import javax.annotation.PostConstruct
import javax.transaction.Transactional

@Service
class ContainerService(
  @Autowired
  val docker: DockerClient
) : BaseService() {
  companion object {
    const val IDE_DOCKER_IMAGE = "cfreak/theia:latest"
    private const val LABEL_PREFIX = "de.code-freak."
    const val LABEL_ANSWER_ID = LABEL_PREFIX + "answer-id"
  }

  private val log = LoggerFactory.getLogger(this::class.java)
  private var idleContainers: Map<String, Long> = mapOf()

  /**
   * Memory limit in bytes
   * Equal to --memory-swap in docker run
   * Default is 512MB
   * Less than 512MB might cause the IDE to crash
   */
  @Value("\${code-freak.docker.memory:536870912}")
  var memory = 0L

  /**
   * Number of CPUs per container
   * Equal to --cpus in docker run
   * Default is 1
   */
  @Value("\${code-freak.docker.cpus:1}")
  var cpus = 0L

  /**
   * Name of the network the container will be attached to
   * Default is the "bridge" network (Docker default)
   */
  @Value("\${code-freak.docker.network:bridge}")
  lateinit var network: String

  @Value("\${code-freak.traefik.url}")
  private lateinit var traefikUrl: String

  @Value("\${code-freak.ide.idle-shutdown-threshold}")
  private lateinit var idleShutdownThreshold: String

  @Autowired
  private lateinit var answerRepository: AnswerRepository

  @Autowired
  private lateinit var containerService: ContainerService

  @PostConstruct
  protected fun init() {
    idleShutdownThreshold.toLong() // fail fast if format is not valid
  }

  /**
   * Start an IDE container for the given submission and returns the container ID
   * If there is already a container for the submission it will be used instead
   */
  fun startIdeContainer(answer: Answer) {
    // either take existing container or create a new one
    val containerId = this.getIdeContainer(answer) ?: this.createIdeContainer(answer)
    // make sure the container is running. Also existing ones could have been stopped
    if (!isContainerRunning(containerId)) {
      docker.startContainer(containerId)
    }
    // prepare the environment after the container has started
    this.prepareIdeContainer(containerId, answer)
  }

  /**
   * Run a command as root inside container and return the result as string
   */
  fun exec(containerId: String, cmd: Array<String>): String {
    val exec = docker.execCreate(
        containerId, cmd,
        DockerClient.ExecCreateParam.attachStdin(), // this is not needed but a workaround for spotify/docker-client#513
        DockerClient.ExecCreateParam.attachStdout(),
        DockerClient.ExecCreateParam.attachStderr(),
        DockerClient.ExecCreateParam.user("root")
    )
    val output = docker.execStart(exec.id())
    return output.readFully()
  }

  /**
   * Get the URL for an IDE container
   * TODO: make this configurable for different types of hosting/reverse proxies/etc
   */
  fun getIdeUrl(answerId: UUID): String {
    return "$traefikUrl/ide/$answerId/"
  }

  /**
   * Try to find an existing container for the given submission
   */
  protected fun getIdeContainer(answer: Answer): String? {
    return docker.listContainers(
        DockerClient.ListContainersParam.withLabel(LABEL_ANSWER_ID, answer.id.toString()),
        DockerClient.ListContainersParam.limitContainers(1)
    ).firstOrNull()?.id()
  }

  @Transactional
  fun saveAnswerFiles(answer: Answer) {
    val containerId = getIdeContainer(answer) ?: throw IllegalArgumentException()
    val files = docker.archiveContainer(containerId, "/home/project/.")
    answer.files = IOUtils.toByteArray(files)
    entityManager.merge(answer)
    log.info("Saved files of container with id: $containerId")
  }

  /**
   * Configure and create a new IDE container.
   * Returns the ID of the created container
   */
  protected fun createIdeContainer(answer: Answer): String {
    val answerId = answer.id.toString()

    val labels = mapOf(
        LABEL_ANSWER_ID to answerId,
        "traefik.enable" to "true",
        "traefik.frontend.rule" to "PathPrefixStrip: /ide/$answerId/",
        "traefik.port" to "3000",
        "traefik.frontend.headers.customResponseHeaders" to "Access-Control-Allow-Origin:*"
    )

    val hostConfig = HostConfig.builder()
        .capAdd("SYS_PTRACE") // required for lsof
        .memory(memory)
        .memorySwap(memory) // memory+swap = memory ==> 0 swap
        .nanoCpus(cpus * 1000000000L)
        .build()

    val containerConfig = ContainerConfig.builder()
        .image(IDE_DOCKER_IMAGE)
        .labels(labels)
        .hostConfig(hostConfig)
        .build()

    val container = docker.createContainer(containerConfig)

    // attach to network
    docker.connectToNetwork(container.id(), network)

    return container.id()!!
  }

  /**
   * Prepare a running container with files and other commands like chmod, etc.
   */
  protected fun prepareIdeContainer(containerId: String, answer: Answer) {
    // extract possible existing files of the current submission into /home/project
    answer.files?.let {
      docker.copyToContainer(it.inputStream(), containerId, "/home/project")
    }
    // change owner from root to theia so we can edit our project files
    exec(containerId, arrayOf("chown", "-R", "theia:theia", "/home/project"))
  }

  protected fun isContainerRunning(containerId: String): Boolean =
      docker.inspectContainer(containerId).state().running()

  @Scheduled(fixedRateString = "\${code-freak.ide.idle-check-rate}", initialDelayString = "\${code-freak.ide.idle-check-rate}")
  protected fun shutdownIdleIdeContainers() {
    log.debug("Checking for idle containers")
    // create a new map to not leak memory if containers disappear in another way
    val newIdleContainers: MutableMap<String, Long> = mutableMapOf()
    docker.listContainers(
        DockerClient.ListContainersParam.withLabel(LABEL_ANSWER_ID),
        DockerClient.ListContainersParam.withStatusRunning())
        .forEach {
          val containerId = it.id()
          // TODO: Use `cat /proc/net/tcp` instead of lsof (requires no privileges)
          val connections = exec(containerId, arrayOf("/opt/code-freak/num-active-connections.sh")).trim()
          if (connections == "0") {
            val now = System.currentTimeMillis()
            val idleSince = idleContainers[containerId] ?: now
            val idleFor = now - idleSince
            log.debug("Container $containerId has been idle for more than $idleFor ms")
            if (idleFor >= idleShutdownThreshold.toLong()) {
              val answerId = it.labels()!![LABEL_ANSWER_ID]
              val answer = answerRepository.findById(UUID.fromString(answerId))
              if (answer.isPresent) {
                containerService.saveAnswerFiles(answer.get())
              } else {
                log.warn("Answer $answerId not found. Files are not saved!")
              }
              log.info("Shutting down container $containerId of answer $answerId")
              docker.stopContainer(containerId, 5)
              docker.removeContainer(containerId)
            } else {
              newIdleContainers[containerId] = idleSince
            }
          }
        }
    idleContainers = newIdleContainers
  }
}