package de.code_freak.codefreak.repository

import de.code_freak.codefreak.entity.Answer
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface AnswerRepository : CrudRepository<Answer, UUID> {
  @Query("SELECT new kotlin.Pair(a.task.id, a.id) FROM Answer a WHERE a.submission.user.id = :userId AND a.task.id IN (:taskIds)")
  fun findIdsForTaskIds(@Param("taskIds") taskIds: Iterable<UUID>, @Param("userId") userId: UUID): Collection<Pair<UUID, UUID>>

  @Query("SELECT a.id FROM Answer a WHERE a.submission.user.id = :userId AND a.task.id = :taskId")
  fun findIdForTaskId(@Param("taskId") taskId: UUID, @Param("userId") userId: UUID): Optional<UUID>
}
