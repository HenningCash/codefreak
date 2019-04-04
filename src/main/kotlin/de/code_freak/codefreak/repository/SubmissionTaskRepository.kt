package de.code_freak.codefreak.repository

import de.code_freak.codefreak.entity.TaskSubmission
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SubmissionTaskRepository : CrudRepository<TaskSubmission, UUID>
