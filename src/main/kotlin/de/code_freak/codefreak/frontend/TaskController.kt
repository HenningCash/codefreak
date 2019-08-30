package de.code_freak.codefreak.frontend

import de.code_freak.codefreak.entity.Submission
import de.code_freak.codefreak.service.AnswerService
import de.code_freak.codefreak.service.ContainerService
import de.code_freak.codefreak.service.LatexService
import de.code_freak.codefreak.service.TaskService
import de.code_freak.codefreak.service.file.FileService
import de.code_freak.codefreak.util.FrontendUtil
import de.code_freak.codefreak.util.TarUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.io.IOException
import java.lang.IllegalArgumentException
import java.util.UUID
import javax.servlet.http.HttpServletResponse

@Controller
class TaskController : BaseController() {

  @Autowired
  lateinit var taskService: TaskService

  @Autowired
  lateinit var answerService: AnswerService

  @Autowired
  lateinit var containerService: ContainerService

  @Autowired
  lateinit var latexService: LatexService

  @Autowired
  lateinit var fileService: FileService

  @Autowired
  lateinit var urls: Urls

  @GetMapping("/tasks/{taskId}/ide")
  fun getAssignmentIde(
    @PathVariable("taskId") taskId: UUID,
    model: Model
  ): String {
    val submission = getOrCreateSubmissionForTask(taskId)
    // start a container based on the submission for the current task
    val answer = submission.getAnswerForTask(taskId)
    containerService.startIdeContainer(answer)
    val containerUrl = containerService.getIdeUrl(answer.id)

    model.addAttribute("ide_url", containerUrl)
    return "ide-redirect"
  }

  @PostMapping("/tasks/{taskId}/answers")
  fun createAnswer(
    @PathVariable("taskId") taskId: UUID
  ): String {
    val submission = getOrCreateSubmissionForTask(taskId)
    containerService.saveAnswerFiles(submission.getAnswerForTask(taskId))
    val assignment = taskService.findTask(taskId).assignment
    return "redirect:${urls.get(assignment)}"
  }

  @GetMapping("/tasks/{taskId}/source.tar", produces = ["application/tar"])
  @ResponseBody
  fun getSourceTar(
    @PathVariable("taskId") taskId: UUID,
    response: HttpServletResponse
  ): StreamingResponseBody {
    val submission = getOrCreateSubmissionForTask(taskId)
    val answer = containerService.saveAnswerFiles(submission.getAnswerForTask(taskId))
    response.setHeader("Content-Disposition", "attachment; filename=source.tar")
    if (fileService.collectionExists(answer.id)) {
      return fileService.readCollectionTar(answer.id).use { FrontendUtil.streamResponse(it) }
    }
    return fileService.readCollectionTar(taskId).use { FrontendUtil.streamResponse(it) }
  }

  @GetMapping("/tasks/{taskId}/source.zip", produces = ["application/zip"])
  @ResponseBody
  fun getSourceZip(
    @PathVariable("taskId") taskId: UUID,
    response: HttpServletResponse
  ): StreamingResponseBody {
    val submission = getOrCreateSubmissionForTask(taskId)
    val answer = containerService.saveAnswerFiles(submission.getAnswerForTask(taskId))
    response.setHeader("Content-Disposition", "attachment; filename=source.zip")
    val tar = fileService.readCollectionTar(if (fileService.collectionExists(answer.id)) answer.id else taskId)
    return tar.use { StreamingResponseBody { out -> TarUtil.tarToZip(it, out) } }
  }

  @PostMapping("/tasks/{taskId}/source")
  fun uploadSource(
    @PathVariable("taskId") taskId: UUID,
    @RequestParam("file") file: MultipartFile,
    model: RedirectAttributes
  ): String {
    val submission = getOrCreateSubmissionForTask(taskId)
    val answer = submission.getAnswerForTask(taskId)
    val filename = file.originalFilename ?: ""
    try {
      when {
        filename.endsWith(".tar", true) -> {
          file.inputStream.use { TarUtil.checkValidTar(it) }
          file.inputStream.use { answerService.setFiles(answer.id, it) }
        }
        filename.endsWith(".zip", true) -> {
          file.inputStream.use { answerService.setFiles(answer.id).use { out -> TarUtil.zipToTar(it, out) } }
        }
        else -> throw IllegalArgumentException("Unsupported file format")
      }
    } catch (e: IOException) {
      throw IllegalArgumentException("File could not be processed")
    }
    model.addFlashAttribute("successMessage", "Successfully uploaded source for task '${answer.task.title}'.")
    return "redirect:" + urls.get(submission.assignment)
  }

  @GetMapping("/tasks/{taskId}/answer.pdf", produces = ["application/pdf"])
  @ResponseBody
  fun pdfExportAnswer(
    @PathVariable("taskId") taskId: UUID,
    response: HttpServletResponse
  ): StreamingResponseBody {
    val submission = getOrCreateSubmissionForTask(taskId)
    val answer = submission.getAnswerForTask(taskId)
    val filename = answer.task.title.trim().replace("[^\\w]+".toRegex(), "-").toLowerCase()
    response.setHeader("Content-Disposition", "attachment; filename=$filename.pdf")
    return StreamingResponseBody { latexService.answerToPdf(answer, it) }
  }

  /**
   * Returns the submission for the given task or creates one if there is none already.
   */
  fun getOrCreateSubmissionForTask(taskId: UUID): Submission {
    val assignmentId = taskService.findTask(taskId).assignment.id
    return super.getOrCreateSubmission(assignmentId)
  }
}
