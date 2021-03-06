/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.gate.security.x509

import com.netflix.spinnaker.gate.security.AllowedAccountsSupport
import com.netflix.spinnaker.gate.services.CredentialsService
import com.netflix.spinnaker.gate.services.PermissionService
import com.netflix.spinnaker.security.User
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken
import org.springframework.stereotype.Component

import java.security.cert.X509Certificate

/**
 * This class is similar to a UserDetailService, but instead of passing in a username to loadUserDetails,
 * it passes in a token containing the x509 certificate. A user can control the principal through the
 * `spring.x509.subjectPrincipalRegex` property.
 */
@Component
@Slf4j
class X509AuthenticationUserDetailsService implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

  private static final String RFC822_NAME_ID = "1"

  @Autowired
  PermissionService permissionService

  @Autowired
  AllowedAccountsSupport allowedAccountsSupport

  @Autowired(required = false)
  X509RolesExtractor rolesExtractor

  @Autowired(required = false)
  X509UserIdentifierExtractor userIdentifierExtractor

  @Override
  UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) throws UsernameNotFoundException {

    if (!(token.credentials instanceof X509Certificate)) {
      return null
    }

    def x509 = (X509Certificate) token.credentials

    def email
    if (userIdentifierExtractor) {
      email = userIdentifierExtractor.fromCertificate(x509)
    }
    if (email == null) {
      email = emailFromSubjectAlternativeName(x509) ?: token.principal
    }

    def roles = []
    if (email) {
      log.debug("Adding email {} to roles.", email)
      roles.add(email)
    }

    String username = email as String
    if (rolesExtractor) {
      def extractedRoles = rolesExtractor.fromCertificate(x509)
      log.debug("Extracted roles {}", extractedRoles)
      roles += extractedRoles
      permissionService.loginWithRoles(username, roles)
    } else {
      permissionService.login(username)
    }

    log.debug("Roles we have now: {}", roles)
    return new User(
        email: email,
        allowedAccounts: allowedAccountsSupport.filterAllowedAccounts(username, roles),
        roles: roles
    )
  }

  /**
   * https://tools.ietf.org/html/rfc3280#section-4.2.1.7
   *
   *  SubjectAltName ::= GeneralNames
   *  GeneralNames ::= SEQUENCE SIZE (1..MAX) OF GeneralName
   *  GeneralName ::= CHOICE {
   *    otherName                       [0]
   *    rfc822Name                      [1]
   *    dNSName                         [2]
   *    x400Address                     [3]
   *    directoryName                   [4]
   *    ediPartyName                    [5]
   *    uniformResourceIdentifier       [6]
   *    iPAddress                       [7]
   *    registeredID                    [8]
   *  }
   */
  static String emailFromSubjectAlternativeName(X509Certificate cert) {
    cert.getSubjectAlternativeNames().find {
      it.find { it.toString() == RFC822_NAME_ID }
    }?.get(1)
  }
}
