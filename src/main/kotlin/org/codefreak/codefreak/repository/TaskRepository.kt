package org.codefreak.codefreak.repository

import org.codefreak.codefreak.entity.Task
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TaskRepository : CrudRepository<Task, UUID> {
  fun findByOwnerIdAndAssignmentIsNullOrderByCreatedAt(ownerId: UUID): Collection<Task>
}
