package dod.p1.keycloak.registration;

import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URLEncoder;

public class RegistrationProtection {

    private final AuthenticationManager.AuthResult auth;
    private KeycloakSession session;

    public RegistrationProtection(KeycloakSession session) {
        this.session = session;
        this.auth = resolveAuthentication(session);
    }

    private AuthenticationManager.AuthResult resolveAuthentication(KeycloakSession keycloakSession) {
        AppAuthManager appAuthManager = new AppAuthManager();
        RealmModel realm = keycloakSession.getContext().getRealm();

        AuthenticationManager.AuthResult authResult = appAuthManager.authenticateIdentityCookie(keycloakSession, realm);
        if (authResult != null) {
            return authResult;
        }

        return null;
    }

    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public InviteCode get() {

        InviteCode code = new InviteCode();

        if (auth == null) {
            Response.status(Response.Status.BAD_REQUEST).build();
            return code;
        }

        AuthenticatorConfigModel authConfig = session.getContext().getRealm().getAuthenticatorConfigs()
                .stream()
                .filter(config -> config.getConfig().get("inviteSecret") != null)
                .findFirst()
                .orElse(null);

        if (authConfig != null) {
            String inviteSecret = authConfig.getConfig().get("inviteSecret");
            String inviteDigest = RegistrationValidation.getInviteDigest(0, inviteSecret);
            String invitedUrlEncoded = URLEncoder.encode(inviteDigest);
            String realm = session.getContext().getRealm().getName();

            code.success = true;
            code.days = Integer.parseInt(authConfig.getConfig().get("inviteSecretDays"));
            code.link = "/auth/realms/" + realm + "/protocol/openid-connect/registrations?client_id=account&response_type=code&invite=" + invitedUrlEncoded;
            return code;
        }

        return code;
    }

    private static class InviteCode {
        public Boolean success = false;
        public int days;
        public String link = "";
    }

}
