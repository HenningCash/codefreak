package de.code_freak.codefreak.service.evaluation

import de.code_freak.codefreak.entity.Evaluation
import de.code_freak.codefreak.repository.EvaluationRepository
import de.code_freak.codefreak.service.EvaluationFinishedEvent
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class EvaluationWriter : ItemWriter<Evaluation> {

  @Autowired
  private lateinit var eventPublisher: ApplicationEventPublisher

  @Autowired
  private lateinit var evaluationRepository: EvaluationRepository

  @Transactional
  override fun write(items: MutableList<out Evaluation>) {
    items.forEach {
      evaluationRepository.save(it)
      eventPublisher.publishEvent(EvaluationFinishedEvent(it))
    }
  }
}
