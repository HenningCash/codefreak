package de.code_freak.codefreak.service

import de.code_freak.codefreak.entity.Assignment
import de.code_freak.codefreak.entity.Task
import de.code_freak.codefreak.entity.User
import de.code_freak.codefreak.repository.TaskRepository
import de.code_freak.codefreak.service.file.FileService
import de.code_freak.codefreak.util.TarUtil.getYamlDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TaskService : BaseService() {

  @Autowired
  private lateinit var taskRepository: TaskRepository

  @Autowired
  private lateinit var fileService: FileService

  @Transactional
  fun findTask(id: UUID): Task = taskRepository.findById(id)
      .orElseThrow { EntityNotFoundException("Task not found") }

  @Transactional
  fun createFromTar(tarContent: ByteArray, assignment: Assignment?, owner: User, position: Long): Task {
    var task = getYamlDefinition<TaskDefinition>(tarContent.inputStream()).let {
      Task(assignment, owner, position, it.title, it.description, 100)
    }
    task = taskRepository.save(task)
    fileService.writeCollectionTar(task.id).use { it.write(tarContent) }
    return task
  }

  @Transactional
  fun updateFromTar(tarContent: ByteArray, taskId: UUID): Task {
    var task = findTask(taskId)
    getYamlDefinition<TaskDefinition>(tarContent.inputStream()).let {
      task.title = it.title
      task.body = it.description
    }
    task = taskRepository.save(task)
    fileService.writeCollectionTar(task.id).use { it.write(tarContent) }
    return task
  }

  fun getTaskDefinition(taskId: UUID) = fileService.readCollectionTar(taskId).use { getYamlDefinition<TaskDefinition>(it) }

  fun getTaskPool(userId: UUID) = taskRepository.findByOwnerIdAndAssignmentIsNullOrderByCreatedAt(userId)
}
