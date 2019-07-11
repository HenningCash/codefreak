package de.code_freak.codefreak.config

import de.code_freak.codefreak.auth.AuthenticationMethod
import de.code_freak.codefreak.auth.DevUserDetailsService
import de.code_freak.codefreak.auth.LdapUserDetailsContextMapper
import de.code_freak.codefreak.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.core.userdetails.UserDetailsService

@Configuration
class SecurityConfiguration : WebSecurityConfigurerAdapter() {

  @Autowired
  lateinit var config: AppConfiguration

  @Autowired
  lateinit var env: Environment

  @Autowired
  lateinit var userRepository: UserRepository

  override fun configure(http: HttpSecurity?) {
    http
        ?.authorizeRequests()
            ?.anyRequest()?.authenticated()
        ?.and()
            ?.formLogin()
        ?.and()
            ?.logout()
  }

  @Bean
  override fun userDetailsService(): UserDetailsService {
    return when (config.authenticationMethod) {
      AuthenticationMethod.SIMPLE -> when (env.acceptsProfiles(Profiles.of("dev", "test"))) {
        true -> DevUserDetailsService()
        false -> throw NotImplementedError("Simple authentication is currently only supported in dev mode.")
      }
      else -> super.userDetailsService()
    }
  }

  override fun configure(auth: AuthenticationManagerBuilder?) {
    when (config.authenticationMethod) {
      AuthenticationMethod.LDAP -> configureLdapAuthentication(auth)
      else -> super.configure(auth)
    }
  }

  private fun configureLdapAuthentication(auth: AuthenticationManagerBuilder?) {
    auth?.ldapAuthentication()
        ?.userDetailsContextMapper(LdapUserDetailsContextMapper(userRepository))
        ?.userSearchBase("ou=people")
        ?.userSearchFilter("(uid={0})")
        ?.groupSearchBase("ou=people")
        ?.groupSearchFilter("member={0}")
        ?.contextSource()
            ?.url("ldap://10.12.12.100:389/dc=planetexpress,dc=com")
  }
}
