package org.codefreak.codefreak.service

import liquibase.util.StreamUtil
import org.codefreak.codefreak.entity.Assignment
import org.codefreak.codefreak.entity.EvaluationStepDefinition
import org.codefreak.codefreak.entity.Task
import org.codefreak.codefreak.entity.User
import org.codefreak.codefreak.repository.AssignmentRepository
import org.codefreak.codefreak.repository.EvaluationStepDefinitionRepository
import org.codefreak.codefreak.repository.TaskRepository
import org.codefreak.codefreak.service.evaluation.runner.CommentRunner
import org.codefreak.codefreak.service.file.FileService
import org.codefreak.codefreak.util.TarUtil
import org.codefreak.codefreak.util.TarUtil.getYamlDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.util.UUID

@Service
class
TaskService : BaseService() {

  @Autowired
  private lateinit var taskRepository: TaskRepository

  @Autowired
  private lateinit var assignmentRepository: AssignmentRepository

  @Autowired
  private lateinit var evaluationStepDefinitionRepository: EvaluationStepDefinitionRepository

  @Autowired
  private lateinit var fileService: FileService

  @Transactional
  fun findTask(id: UUID): Task = taskRepository.findById(id)
      .orElseThrow { EntityNotFoundException("Task not found") }

  @Transactional
  fun createFromTar(tarContent: ByteArray, assignment: Assignment?, owner: User, position: Long): Task {
    val definition = getYamlDefinition<TaskDefinition>(tarContent.inputStream())
    var task = Task(assignment, owner, position, definition.title, definition.description, 100)
    task.hiddenFiles = definition.hidden
    task.protectedFiles = definition.protected

    task = taskRepository.save(task)
    task.evaluationStepDefinitions = definition.evaluation
        .mapIndexed { index, it -> EvaluationStepDefinition(task, it.step, index, it.options ) }
        .toMutableSet()

    evaluationStepDefinitionRepository.saveAll(task.evaluationStepDefinitions)
    task = taskRepository.save(task)
    fileService.writeCollectionTar(task.id).use { fileCollection ->
      TarUtil.copyEntries(tarContent.inputStream(), fileCollection) { !it.name.equals("codefreak.yml", true) }
    }
    return task
  }

  @Transactional
  fun createEmptyTask(owner: User): Task {
    return ByteArrayOutputStream().use {
      StreamUtil.copy(ClassPathResource("empty_task.tar").inputStream, it)
      createFromTar(it.toByteArray(), null, owner, 0)
    }
  }

  @Transactional
  fun deleteTask(id: UUID) = taskRepository.deleteById(id)

  private fun applyDefaultRunners(taskDefinition: TaskDefinition): TaskDefinition {
    // add "comments" runner by default if not defined
    taskDefinition.run {
      if (evaluation.find { it.step == CommentRunner.RUNNER_NAME } == null) {
        return copy(evaluation = evaluation.toMutableList().apply {
          add(EvaluationDefinition(CommentRunner.RUNNER_NAME))
        })
      }
    }
    return taskDefinition
  }

  @Transactional
  fun saveTask(task: Task) = taskRepository.save(task)

  @Transactional
  fun setTaskPosition(task: Task, newPosition: Long) {
    val assignment = task.assignment
    require(assignment != null) { "Task is not part of an assignment" }
    require(newPosition < assignment.tasks.size && newPosition >= 0) { "Invalid position" }

    if (task.position == newPosition) {
      return
    }
    if (task.position < newPosition) {
      /*  0
          1 --
          2  |
          3 <|
          4

          0 -> 0 // +- 0
          1 -> 3 // = 3
          2 -> 1 // -1
          3 -> 2 // -1
          4 -> 4 // +- 0
       */
      assignment.tasks
          .filter { it.position > task.position && it.position <= newPosition }
          .forEach { it.position -- }
    } else {
      /*  0
          1 <|
          2  |
          3 --
          4

          0 -> 0 // +- 0
          1 -> 2 // +1
          2 -> 3 // +1
          3 -> 1 // = 1
          4 -> 4 // +- 0
       */
      assignment.tasks
          .filter { it.position < task.position && it.position >= newPosition }
          .forEach { it.position ++ }
    }

    task.position = newPosition
    taskRepository.saveAll(assignment.tasks)
    assignmentRepository.save(assignment)
  }

  fun getTaskPool(userId: UUID) = taskRepository.findByOwnerIdAndAssignmentIsNullOrderByCreatedAt(userId)
}
