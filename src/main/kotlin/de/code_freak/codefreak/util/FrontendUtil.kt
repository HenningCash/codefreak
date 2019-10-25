package de.code_freak.codefreak.util

import de.code_freak.codefreak.entity.User
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

object FrontendUtil {
  fun getRequest() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request

  fun getCurrentUser() = SecurityContextHolder.getContext().authentication.principal as User
}
