package de.code_freak.codefreak.frontend

import com.hsingh.shortuuid.ShortUuid
import de.code_freak.codefreak.entity.Answer
import de.code_freak.codefreak.entity.Assignment
import de.code_freak.codefreak.entity.BaseEntity
import de.code_freak.codefreak.entity.Evaluation
import de.code_freak.codefreak.entity.Task
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class Urls {
  private val shortUuidBuilder = ShortUuid.Builder()

  private val BaseEntity.shortId get() = getShortId(id)

  fun get(task: Task) = "/tasks/" + task.shortId

  fun get(assignment: Assignment) = "/assignments/" + assignment.shortId

  fun get(evaluation: Evaluation) = "/evaluations/" + evaluation.shortId

  fun get(answer: Answer) = "/answers/" + answer.shortId

  fun getLtiLaunch(assignment: Assignment) = "/lti/launch/${assignment.shortId}"

  fun getShortId(id: UUID) = shortUuidBuilder.build(id).toString()
}
