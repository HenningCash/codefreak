package org.codefreak.codefreak.service

data class TaskDefinition(
  val title: String,
  val id: String? = null,
  val description: String? = null,
  val hidden: List<String> = emptyList(),
  val protected: List<String> = emptyList(),
  val evaluation: List<EvaluationDefinition> = emptyList(),
  val updatedAt: String? = null,
  val ide: IdeDefinition? = null
) {
  private constructor() : this("")
}

data class EvaluationDefinition(
  val step: String,
  val options: Map<String, Any> = emptyMap(),
  val title: String? = null,
  val id: String? = null
) {
  private constructor() : this("")
}

data class AssignmentDefinition(
  val title: String,
  val tasks: List<String>
) {
  private constructor() : this("", emptyList())
}

data class IdeDefinition(
  val enabled: Boolean,
  val image: String?
) {
  constructor() : this(true, null)
}
