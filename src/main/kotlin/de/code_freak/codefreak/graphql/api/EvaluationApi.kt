package de.code_freak.codefreak.graphql.api

import com.expediagroup.graphql.annotations.GraphQLID
import com.expediagroup.graphql.annotations.GraphQLIgnore
import com.expediagroup.graphql.annotations.GraphQLName
import de.code_freak.codefreak.entity.Evaluation
import de.code_freak.codefreak.entity.EvaluationStep
import de.code_freak.codefreak.graphql.ServiceAccess
import de.code_freak.codefreak.service.EvaluationDefinition

@GraphQLName("EvaluationStepDefinition")
class EvaluationStepDefinitionDto(val index: Int, @GraphQLIgnore val definition: EvaluationDefinition) {
  val runnerName = definition.step
}

@GraphQLName("Evaluation")
class EvaluationDto(@GraphQLIgnore val entity: Evaluation, @GraphQLIgnore val serviceAccess: ServiceAccess) {

  @GraphQLID
  val id = entity.id
  val answer by lazy { AnswerDto(entity.answer, serviceAccess) }
  val createdAt = entity.createdAt
  val results by lazy { entity.evaluationSteps.map { EvaluationResultDto(it) } }
}

@GraphQLName("EvaluationResult")
class EvaluationResultDto(@GraphQLIgnore val entity: EvaluationStep) {
  val runnerName = entity.runnerName
  val position = entity.position
  val error = entity.result == EvaluationStep.EvaluationStepResult.ERRORED
}
