/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.keycloak.services.resources.flows;

import org.jboss.resteasy.logging.Logger;
import org.jboss.resteasy.spi.HttpRequest;
import org.keycloak.models.ApplicationModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.OAuthClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RequiredCredentialModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserModel.RequiredAction;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.services.managers.AccessCodeEntry;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.TokenManager;
import org.keycloak.services.resources.TokenService;

import javax.ws.rs.Path;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.util.HashSet;
import java.util.Set;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class OAuthFlows {

    private static final Logger log = Logger.getLogger(OAuthFlows.class);

    private RealmModel realm;

    private HttpRequest request;

    private UriInfo uriInfo;

    private AuthenticationManager authManager;

    private TokenManager tokenManager;

    OAuthFlows(RealmModel realm, HttpRequest request, UriInfo uriInfo, AuthenticationManager authManager,
            TokenManager tokenManager) {
        this.realm = realm;
        this.request = request;
        this.uriInfo = uriInfo;
        this.authManager = authManager;
        this.tokenManager = tokenManager;
    }

    public Response redirectAccessCode(AccessCodeEntry accessCode, String state, String redirect) {
        return redirectAccessCode(accessCode, state, redirect, false);
    }


    public Response redirectAccessCode(AccessCodeEntry accessCode, String state, String redirect, boolean rememberMe) {
        String code = accessCode.getCode();

        if (Constants.INSTALLED_APP_URN.equals(redirect)) {
            return Flows.forms(realm, request, uriInfo).setAccessCode(accessCode.getId(), code).createCode();
        } else {
            UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam("code", code);
            log.debug("redirectAccessCode: state: {0}", state);
            if (state != null)
                redirectUri.queryParam("state", state);
            Response.ResponseBuilder location = Response.status(302).location(redirectUri.build());
            Cookie remember = request.getHttpHeaders().getCookies().get(AuthenticationManager.KEYCLOAK_REMEMBER_ME);
            rememberMe = rememberMe || remember != null;
            location.cookie(authManager.createLoginCookie(realm, accessCode.getUser(), uriInfo, rememberMe));
            return location.build();
        }
    }

    public Response redirectError(ClientModel client, String error, String state, String redirect) {
        if (Constants.INSTALLED_APP_URN.equals(redirect)) {
            return Flows.forms(realm, request, uriInfo).setError(error).createCode();
        } else {
            UriBuilder redirectUri = UriBuilder.fromUri(redirect).queryParam("error", error);
            if (state != null) {
                redirectUri.queryParam("state", state);
            }
            return Response.status(302).location(redirectUri.build()).build();
        }
    }

    public Response processAccessCode(String scopeParam, String state, String redirect, ClientModel client, UserModel user) {
        return processAccessCode(scopeParam, state, redirect, client, user, false);
    }


    public Response processAccessCode(String scopeParam, String state, String redirect, ClientModel client, UserModel user, boolean rememberMe) {
        isTotpConfigurationRequired(user);
        isEmailVerificationRequired(user);

        boolean isResource = client instanceof ApplicationModel;
        AccessCodeEntry accessCode = tokenManager.createAccessCode(scopeParam, state, redirect, realm, client, user);
        log.debug("processAccessCode: isResource: {0}", isResource);
        log.debug("processAccessCode: go to oauth page?: {0}",
                (!isResource && (accessCode.getRealmRolesRequested().size() > 0 || accessCode.getResourceRolesRequested()
                        .size() > 0)));

        Set<RequiredAction> requiredActions = user.getRequiredActions();
        if (!requiredActions.isEmpty()) {
            accessCode.setRequiredActions(new HashSet<UserModel.RequiredAction>(requiredActions));
            accessCode.setExpiration(System.currentTimeMillis() / 1000 + realm.getAccessCodeLifespanUserAction());
            return Flows.forms(realm, request, uriInfo).setAccessCode(accessCode.getId(), accessCode.getCode()).setUser(user)
                    .createResponse(user.getRequiredActions().iterator().next());
        }

        if (!isResource
                && (accessCode.getRealmRolesRequested().size() > 0 || accessCode.getResourceRolesRequested().size() > 0)) {
            accessCode.setExpiration(System.currentTimeMillis() / 1000 + realm.getAccessCodeLifespanUserAction());
            return Flows.forms(realm, request, uriInfo).setAccessCode(accessCode.getId(), accessCode.getCode()).
                    setAccessRequest(accessCode.getRealmRolesRequested(), accessCode.getResourceRolesRequested()).
                    setClient(client).createOAuthGrant();
        }

        if (redirect != null) {
            return redirectAccessCode(accessCode, state, redirect, rememberMe);
        } else {
            return null;
        }
    }

    public Response forwardToSecurityFailure(String message) {
        return Flows.forms(realm, request, uriInfo).setError(message).createErrorPage();
    }

    private void isTotpConfigurationRequired(UserModel user) {
        for (RequiredCredentialModel c : realm.getRequiredCredentials()) {
            if (c.getType().equals(CredentialRepresentation.TOTP) && !user.isTotp()) {
                user.addRequiredAction(RequiredAction.CONFIGURE_TOTP);
                log.debug("User is required to configure totp");
            }
        }
    }

    private void isEmailVerificationRequired(UserModel user) {
        if (realm.isVerifyEmail() && !user.isEmailVerified()) {
            user.addRequiredAction(RequiredAction.VERIFY_EMAIL);
            log.debug("User is required to verify email");
        }
    }

}
