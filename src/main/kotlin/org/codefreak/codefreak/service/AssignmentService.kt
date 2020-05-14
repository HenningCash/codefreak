package org.codefreak.codefreak.service

import org.codefreak.codefreak.entity.Assignment
import org.codefreak.codefreak.entity.Task
import org.codefreak.codefreak.entity.User
import org.codefreak.codefreak.repository.AssignmentRepository
import org.codefreak.codefreak.service.file.FileService
import org.codefreak.codefreak.util.TarUtil
import liquibase.util.StreamUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.Exception
import java.util.UUID

@Service
class AssignmentService : BaseService() {

  data class AssignmentCreationResult(val assignment: Assignment, val taskErrors: Map<String, Throwable>)

  @Autowired
  private lateinit var assignmentRepository: AssignmentRepository

  @Autowired
  private lateinit var submissionService: SubmissionService

  @Autowired
  private lateinit var taskService: TaskService

  @Autowired
  private lateinit var self: AssignmentService

  @Autowired
  private lateinit var fileService: FileService

  @Transactional
  fun findAssignment(id: UUID): Assignment = assignmentRepository.findById(id)
      .orElseThrow { EntityNotFoundException("Assignment not found") }

  @Transactional
  fun findAllAssignments(): Iterable<Assignment> = assignmentRepository.findAll()

  @Transactional
  fun findAssignmentsByOwner(owner: User): Iterable<Assignment> = assignmentRepository.findByOwnerId(owner.id)

  @Transactional
  fun findAllAssignmentsForUser(userId: UUID) = submissionService.findSubmissionsOfUser(userId)
      .filter { it.assignment != null }
      .map { it.assignment!! }
      .filter { it.active }

  @Transactional
  fun createFromTar(content: ByteArray, owner: User, modify: Assignment.() -> Unit = {}): AssignmentCreationResult {
    val definition = TarUtil.getYamlDefinition<AssignmentDefinition>(ByteArrayInputStream(content))
    val assignment = self.withNewTransaction {
      val assignment = Assignment(definition.title, owner)
      assignment.modify()
      assignmentRepository.save(assignment)
    }
    val taskErrors = mutableMapOf<String, Throwable>()
    definition.tasks.forEachIndexed { index, it ->
      val taskContent = ByteArrayOutputStream()
      TarUtil.extractSubdirectory(ByteArrayInputStream(content), taskContent, it)
      try {
        self.withNewTransaction {
          taskService.createFromTar(taskContent.toByteArray(), assignment, owner, index.toLong()).let {
            assignment.tasks.add(it)
          }
        }
      } catch (e: Exception) {
        taskErrors[it] = e
      }
    }
    return AssignmentCreationResult(assignment, taskErrors)
  }

  @Transactional
  fun createEmptyAssignment(owner: User): Assignment {
    return ByteArrayOutputStream().use {
      StreamUtil.copy(ClassPathResource("empty_assignment.tar").inputStream, it)
      createFromTar(it.toByteArray(), owner).assignment
    }
  }

  @Transactional
  fun deleteAssignment(id: UUID) = assignmentRepository.deleteById(id)

  @Transactional
  fun addTasksToAssignment(assignment: Assignment, tasks: Collection<Task>) {
    var nextPosition = assignment.tasks.maxBy { it.position }?.let { it.position + 1 } ?: 0
    for (task in tasks) {
      taskService.createFromTar(taskService.getExportTar(task), assignment, assignment.owner, nextPosition)
      nextPosition++
    }
  }

  @Transactional
  fun saveAssignment(assignment: Assignment) = assignmentRepository.save(assignment)
}
