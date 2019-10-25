package de.code_freak.codefreak.auth

import de.code_freak.codefreak.config.AppConfiguration
import de.code_freak.codefreak.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.ldap.core.DirContextAdapter
import org.springframework.ldap.core.DirContextOperations
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper
import org.springframework.stereotype.Component

@Component
class LdapUserDetailsContextMapper : UserDetailsContextMapper {

  @Autowired
  private lateinit var userService: UserService

  @Autowired
  private lateinit var config: AppConfiguration

  private val log = LoggerFactory.getLogger(this::class.java)
  private val mappings by lazy {
    // DefaultLdapAuthoritiesPopulator converts roles to uppercase and prefixes them with ROLE_
    // this does not happen in the active directory provider
    if (config.ldap.activeDirectory) {
      config.ldap.roleMappings
    } else {
      config.ldap.roleMappings.mapKeys { "ROLE_" + it.key.toUpperCase() }
    }
  }

  override fun mapUserToContext(user: UserDetails?, ctx: DirContextAdapter?) {
    throw UnsupportedOperationException()
  }

  override fun mapUserFromContext(ctx: DirContextOperations?, username: String?, authorities: MutableCollection<out GrantedAuthority>?): UserDetails {
    val roles = mutableListOf<Role>()

    if (config.ldap.overrideRoles.containsKey(username)) {
      roles.add(config.ldap.overrideRoles[username]!!)
    } else {
      authorities?.forEach {
        val role = mappings[it.authority]
        if (role != null) {
          roles.add(role)
        }
      }
    }

    val user = userService.getOrCreateUser(username!!) {
      firstName = config.ldap.firstNameAttribute?.let { ctx?.getStringAttribute(it) }
      lastName = config.ldap.lastNameAttribute?.let { ctx?.getStringAttribute(it) }
      this.roles = roles.toSet()
    }
    log.debug("Logging in ${user.username} with roles $roles")
    return user
  }
}
