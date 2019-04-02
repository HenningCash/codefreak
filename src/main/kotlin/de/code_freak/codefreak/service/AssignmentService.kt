package de.code_freak.codefreak.service

import de.code_freak.codefreak.entity.Assignment
import de.code_freak.codefreak.repository.AssignmentRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID
import javax.transaction.Transactional

@Service
class AssignmentService {
  @Autowired
  lateinit var assignmentRepository: AssignmentRepository

  @Transactional
  fun findAssignment(id: UUID): Assignment = assignmentRepository.findById(id)
    .orElseThrow { EntityNotFoundException("Assignment not found") }
}
