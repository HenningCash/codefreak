package org.codefreak.codefreak.service.evaluation

import org.codefreak.codefreak.entity.Answer
import org.codefreak.codefreak.entity.Evaluation
import org.codefreak.codefreak.entity.EvaluationStep
import org.codefreak.codefreak.entity.EvaluationStep.EvaluationStepResult
import org.codefreak.codefreak.entity.Feedback
import org.codefreak.codefreak.service.file.FileService
import org.codefreak.codefreak.util.error
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.concurrent.thread

@Component
class AnswerProcessor : ItemProcessor<Answer, Evaluation> {

  @Autowired
  private lateinit var fileService: FileService

  @Autowired
  private lateinit var evaluationService: EvaluationService

  @Autowired
  private lateinit var taskScheduler: TaskScheduler

  @Value("#{@config.evaluation.defaultTimeout}")
  private var defaultTimeout: Long = 0L

  private val log = LoggerFactory.getLogger(this::class.java)

  override fun process(answer: Answer): Evaluation {
    val digest = fileService.getCollectionMd5Digest(answer.id)
    val evaluation = evaluationService.getOrCreateValidEvaluationByDigest(answer, digest)
    log.debug("Start evaluation of answer {} ({} steps)", answer.id, answer.task.evaluationStepDefinitions.size)
    answer.task.evaluationStepDefinitions.filter { it.active }.forEach { evaluationStepDefinition ->
      val executedStep = evaluation.evaluationSteps.find { it.definition == evaluationStepDefinition }
      if (executedStep !== null) {
        // Only re-run if this step errored
        if (executedStep.result !== EvaluationStepResult.ERRORED) {
          return@forEach
        }
        // remove existing errored step from evaluation for re-running
        evaluation.evaluationSteps.remove(executedStep)
      }
      val runnerName = evaluationStepDefinition.runnerName
      val evaluationStep = EvaluationStep(evaluationStepDefinition, evaluation)
      try {
        val runner = evaluationService.getEvaluationRunner(runnerName)
        val feedbackList = runEvaluation(runner, evaluationStep)
        evaluationStep.addAllFeedback(feedbackList)
        // only check for explicitly "failed" feedback so we ignore the "skipped" ones
        if (evaluationStep.feedback.any { feedback -> feedback.isFailed }) {
          evaluationStep.result = EvaluationStep.EvaluationStepResult.FAILED
        } else {
          evaluationStep.result = EvaluationStep.EvaluationStepResult.SUCCESS
        }
        evaluationStep.summary = runner.summarize(feedbackList)
      } catch (e: EvaluationStepException) {
        evaluationStep.result = e.result
        evaluationStep.summary = e.message
      } catch (e: Exception) {
        log.error(e)
        evaluationStep.result = EvaluationStepResult.ERRORED
        evaluationStep.summary = e.message ?: "Unknown error"
      }

      log.debug(
          "Step $runnerName finished ${evaluationStep.result}: ${evaluationStep.summary}"
      )
      evaluation.addStep(evaluationStep)
    }
    log.debug("Finished evaluation of answer {}", answer.id)
    return evaluation
  }

  private fun runEvaluation(runner: EvaluationRunner, step: EvaluationStep): List<Feedback> {
    val evaluationStepDefinition = step.definition
    val answer = step.evaluation.answer
    var feedbackList: List<Feedback> = listOf()
    var exception: Exception? = null
    val evaluationThread = thread(name = "cf-evaluation-${step.id}") {
      try {
        feedbackList = runner.run(answer, evaluationStepDefinition.options)
      } catch (e: InterruptedException) {
        log.debug("Interrupted execution of evaluation step ${step.id}")
      } catch (e: Exception) {
        exception = e
      }
    }
    val timeout = step.definition.timeout ?: defaultTimeout
    log.debug("Running evaluation step with runner '{}' and a timeout of $timeout seconds", runner.getName())
    val scheduledTask = if (timeout > 0L) {
      taskScheduler.schedule({
        if (evaluationThread.isAlive) {
          log.debug("Interrupting runner ${runner.getName()} thread")
          evaluationThread.interrupt()
        }
      }, Instant.now().plusSeconds(timeout))
    } else {
      log.debug("Not scheduling a timeout for step ${step.id}")
      null
    }
    evaluationThread.join()
    scheduledTask?.cancel(true)
    if (evaluationThread.isInterrupted) {
      log.info("Timeout for evaluation of step ${step.definition.runnerName} of answer ${answer.id} occurred after ${timeout}sec")
      runner.stop(answer)
      throw EvaluationStepException("Evaluation timed out after $timeout seconds", EvaluationStepResult.ERRORED)
    }
    exception?.let { throw it }
    return feedbackList
  }
}
