package de.code_freak.codefreak.frontend

import de.code_freak.codefreak.auth.Authority
import de.code_freak.codefreak.service.file.FileService
import de.code_freak.codefreak.util.TarUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.util.UUID
import javax.servlet.http.HttpServletResponse

@Controller
@RequestMapping("/answers")
class AnswerController : BaseController() {

  @Autowired
  lateinit var fileService: FileService

  @Secured(Authority.ROLE_TEACHER)
  @GetMapping("/{answerId}/source.zip", produces = ["application/zip"])
  @ResponseBody
  fun getSourceZip(
    @PathVariable("answerId") answerId: UUID,
    response: HttpServletResponse
  ): HttpEntity<StreamingResponseBody> {
    val answer = answerService.getAnswer(answerId)
    fileService.readCollectionTar(answer.id).use {
      return download("${answer.submission.user.username}_${answer.task.title}.zip") { out ->
        TarUtil.tarToZip(it, out)
      }
    }
  }

  @Secured(Authority.ROLE_TEACHER)
  @GetMapping("/{answerId}/source.tar", produces = ["application/tar"])
  @ResponseBody
  fun getSourceTar(
    @PathVariable("answerId") answerId: UUID,
    response: HttpServletResponse
  ): HttpEntity<StreamingResponseBody> {
    val answer = answerService.getAnswer(answerId)
    fileService.readCollectionTar(answer.id).use {
      return download("${answer.submission.user.username}_${answer.task.title}.tar", it)
    }
  }
}
