package dod.p1.keycloak.registration;

import org.keycloak.authentication.FormContext;
import org.keycloak.authentication.ValidationContext;
import org.keycloak.authentication.forms.RegistrationPage;
import org.keycloak.authentication.forms.RegistrationProfile;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;

import javax.ws.rs.core.MultivaluedMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static dod.p1.keycloak.common.CommonConfig.*;
import static dod.p1.keycloak.registration.X509Tools.getCACUsername;
import static dod.p1.keycloak.registration.X509Tools.isCACRegistered;

public class RegistrationValidation extends RegistrationProfile {

    public static final String PROVIDER_ID = "registration-validation-action";
    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();
    private static final HashMap<String, String> INVITE_CACHE = new HashMap<>();

    private static final String PROPERTY_IL2_DOMAINS = "il2ApprovedDomains";
    private static final String PROPERTY_IL4_DOMAINS = "il4ApprovedDomains";
    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED
    };

    static {
        // Add the domain list configuration
        ProviderConfigProperty il2DomainProperty;
        il2DomainProperty = new ProviderConfigProperty();
        il2DomainProperty.setName(PROPERTY_IL2_DOMAINS);
        il2DomainProperty.setLabel("IL2 Approved Domains");
        il2DomainProperty.setType(ProviderConfigProperty.MULTIVALUED_STRING_TYPE);
        il2DomainProperty.setDefaultValue(".mil");
        il2DomainProperty.setHelpText("List email domains authorized to register");
        CONFIG_PROPERTIES.add(il2DomainProperty);

        ProviderConfigProperty il4DomainProperty;
        il4DomainProperty = new ProviderConfigProperty();
        il4DomainProperty.setName(PROPERTY_IL4_DOMAINS);
        il4DomainProperty.setLabel("IL4 Approved Domains");
        il4DomainProperty.setType(ProviderConfigProperty.MULTIVALUED_STRING_TYPE);
        il4DomainProperty.setDefaultValue(".mil");
        il4DomainProperty.setHelpText("List email domains that will be auto-promoted to IL4");
        CONFIG_PROPERTIES.add(il4DomainProperty);

        // Add the invited secret configuration
        ProviderConfigProperty inviteProperty;
        inviteProperty = new ProviderConfigProperty();
        inviteProperty.setName("inviteSecret");
        inviteProperty.setLabel("Invite Secret");
        inviteProperty.setType(ProviderConfigProperty.PASSWORD);
        inviteProperty.setDefaultValue("replace_me_C1k0cFGcXztrClL3qc6ARSdImz8ZhDNjTUhr4dxEKO3ZbYCJ0MshBHGnOb1mRgC");
        inviteProperty.setHelpText("This is the secret used to generate invite links, use a strong generator such as openssl for this.");
        CONFIG_PROPERTIES.add(inviteProperty);

        // Add invite secret maximum days limit
        ProviderConfigProperty inviteDaysProperty;
        inviteDaysProperty = new ProviderConfigProperty();
        inviteDaysProperty.setName("inviteSecretDays");
        inviteDaysProperty.setLabel("Invite link days");
        inviteDaysProperty.setHelpText("The number of days invite links are valid for");
        inviteDaysProperty.setType(ProviderConfigProperty.LIST_TYPE);
        inviteDaysProperty.setDefaultValue("5");
        List<String> daysOptions = Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        inviteDaysProperty.setOptions(daysOptions);
        CONFIG_PROPERTIES.add(inviteDaysProperty);
    }

    /**
     * @param dayLookBack  Number of days from today to look back for a matching digest
     * @param inviteSecret The secret used to generate the digest
     * @return true if a valid digest was matched
     */
    static String getInviteDigest(int dayLookBack, String inviteSecret) {
        Instant instant = Instant.now();
        Instant instantOffset = instant.minus(dayLookBack, ChronoUnit.DAYS);
        Date day = Date.from(instantOffset);
        SimpleDateFormat formatDate = new SimpleDateFormat("yyyyD");
        String dayKey = formatDate.format(day);

        if (!INVITE_CACHE.containsKey(dayKey)) {
            MessageDigest digest;

            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                return "";
            }

            String combinedSecret = dayKey + ":" + inviteSecret;
            byte[] hash = digest.digest(combinedSecret.getBytes(StandardCharsets.UTF_8));
            String computedInvite = Base64.getEncoder().encodeToString(hash);
            INVITE_CACHE.put(dayKey, computedInvite);
        }

        return INVITE_CACHE.get(dayKey);
    }

    private static void bindRequiredActions(UserModel user, String cacUsername) {
        // Default actions for all users
        user.addRequiredAction(UserModel.RequiredAction.VERIFY_EMAIL);
        user.addRequiredAction(UserModel.RequiredAction.TERMS_AND_CONDITIONS);

        if (cacUsername == null) {
            // This user must configure MFA for their login
            user.addRequiredAction(UserModel.RequiredAction.CONFIGURE_TOTP);
        }
    }

    private static void processX509UserAttribute(UserModel user, String cacUsername) {
        if (cacUsername != null) {
            // Bind the cac attribute to the user
            user.setSingleAttribute(X509_USER_ATTRIBUTE, cacUsername);
        }
    }

    private static void joinValidUserToILGroups(FormContext context, UserModel user, String cacUsername) {
        String email = user.getEmail().toLowerCase();
        AuthenticatorConfigModel authenticatorConfig = context.getAuthenticatorConfig();
        Map<String, String> config = authenticatorConfig.getConfig();

        GroupModel il2Group = context.getRealm().getGroupById(IL2_GROUP_ID);
        GroupModel il4Group = context.getRealm().getGroupById(IL4_GROUP_ID);
        GroupModel il5Group = context.getRealm().getGroupById(IL5_GROUP_ID);

        String[] il4EmailDomains = config.getOrDefault(PROPERTY_IL4_DOMAINS, ".mil").split("##");
        boolean isValidIL4Email = Stream.of(il4EmailDomains).anyMatch(email::endsWith);

        // All valid users should be joined to the IL2 group
        user.joinGroup(il2Group);

        if (isValidIL4Email) {
            user.joinGroup(il4Group);
        }

        if (cacUsername != null) {
            // CAC users should be in the IL2/4/5 groups regardless of their email address
            user.joinGroup(il4Group);
            user.joinGroup(il5Group);
        }
    }

    /**
     * Add a custom user attribute (mattermostid) to enable direct mattermost <> keycloak auth on mattermost teams edition
     *
     * @param formData The user registration form data
     */
    private static void generateUniqueStringIdForMattermost(MultivaluedMap<String, String> formData, UserModel user) {
        String email = formData.getFirst(Validation.FIELD_EMAIL);

        byte[] encodedEmail;
        int emailByteTotal = 0;
        Date today = new Date();

        encodedEmail = email.getBytes(StandardCharsets.US_ASCII);
        for (byte b : encodedEmail) {
            emailByteTotal += b;
        }

        SimpleDateFormat formatDate = new SimpleDateFormat("yyDHmsS");

        user.setSingleAttribute("mattermostid", formatDate.format(today) + emailByteTotal);
    }

    /**
     * @param authenticatorConfig
     * @param inviteCode
     * @return boolean
     */
    private static boolean isValidInviteCode(AuthenticatorConfigModel authenticatorConfig, String inviteCode) {

        if (inviteCode == null) {
            return false;
        }

        try {
            // Fail if the invite isn't a valid Base64 string
            Base64.getDecoder().decode(inviteCode);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        // Load the configured secret
        String inviteSecret = authenticatorConfig.getConfig().get("inviteSecret");
        int inviteSecretDays = Integer.parseInt(authenticatorConfig.getConfig().get("inviteSecretDays")) + 1;

        for (int dayOffset = 0; dayOffset < inviteSecretDays; dayOffset++) {
            String inviteDigest = getInviteDigest(dayOffset, inviteSecret);
            if (inviteCode.equals(inviteDigest)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Test a given email address for valid format and match against the IL2 & IL4 domain lists
     *
     * @param authenticatorConfig The current config model
     * @param email               The email to test
     * @return true if the email validates
     */
    private static boolean isValidEmailAddress(AuthenticatorConfigModel authenticatorConfig, String email) {
        Map<String, String> config = authenticatorConfig.getConfig();

        String[] il2EmailDomains = config.getOrDefault(PROPERTY_IL2_DOMAINS, "mil").split("##");
        String[] il4EmailDomains = config.getOrDefault(PROPERTY_IL4_DOMAINS, "mil").split("##");

        if (Validation.isBlank(email) || !Validation.isEmailValid(email)) {
            return false;
        }

        String emailLowerCase = email.toLowerCase();

        // validate email domain based on IL2 & IL4 domain lists
        return Stream.of(il2EmailDomains, il4EmailDomains).flatMap(Stream::of)
                .anyMatch(domain -> {
                    if (domain.contains(".")) {
                        return emailLowerCase.endsWith("@" + domain) || emailLowerCase.endsWith("." + domain);
                    } else {
                        return emailLowerCase.endsWith("." + domain);
                    }
                });
    }

    @Override
    public void success(FormContext context) {
        UserModel user = context.getUser();
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String cacUsername = getCACUsername(context);

        generateUniqueStringIdForMattermost(formData, user);
        joinValidUserToILGroups(context, user, cacUsername);
        processX509UserAttribute(user, cacUsername);
        bindRequiredActions(user, cacUsername);
    }

    @Override
    public void buildPage(FormContext context, LoginFormsProvider form) {
        String cacUsername = getCACUsername(context);
        if (cacUsername != null) {
            form.setAttribute("cacIdentity", cacUsername);
        }
    }

    @Override
    public String getDisplayType() {
        return "Platform One Registration Validation";
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public String getHelpText() {
        return "Restrict user registration to specific top-level-domains.  Important: the user must use the format \".mil\"";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public void validate(ValidationContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();

        AuthenticatorConfigModel authenticatorConfig = context.getAuthenticatorConfig();

        List<FormMessage> errors = new ArrayList<>();
        String inviteCode = formData.getFirst("invite");
        String username = formData.getFirst(Validation.FIELD_USERNAME);
        String email = formData.getFirst(Validation.FIELD_EMAIL);

        String eventError = Errors.INVALID_REGISTRATION;

        if (Validation.isBlank(username)) {
            errors.add(new FormMessage(Validation.FIELD_USERNAME, Messages.MISSING_USERNAME));
        }

        // Username validation based on Mattermost requirements.
        if (username != null) {
            if (!username.matches("[A-Za-z0-9-_.]+")) {
                errors.add(new FormMessage(Validation.FIELD_USERNAME, "Username can only contain alphanumeric, underscore, hyphen and period characters."));
            }

            if (!Character.isLetter(username.charAt(0))) {
                errors.add(new FormMessage(Validation.FIELD_USERNAME, "Username must begin with a letter."));
            }

            if (username.length() < 3 || username.length() > 22) {
                errors.add(new FormMessage(Validation.FIELD_USERNAME, "Username must be between 3 to 22 characters."));
            }
        }

        if (Validation.isBlank(formData.getFirst(Validation.FIELD_FIRST_NAME))) {
            errors.add(new FormMessage(Validation.FIELD_FIRST_NAME, Messages.MISSING_FIRST_NAME));
        }

        if (Validation.isBlank(formData.getFirst(Validation.FIELD_LAST_NAME))) {
            errors.add(new FormMessage(Validation.FIELD_LAST_NAME, Messages.MISSING_LAST_NAME));
        }

        if (Validation.isBlank(formData.getFirst("user.attributes.affiliation"))) {
            errors.add(new FormMessage("user.attributes.affiliation", "Please specify your organization affiliation."));
        }

        if (Validation.isBlank(formData.getFirst("user.attributes.rank"))) {
            errors.add(new FormMessage("user.attributes.rank", "Please specify your rank or choose n/a."));
        }

        if (Validation.isBlank(formData.getFirst("user.attributes.organization"))) {
            errors.add(new FormMessage("user.attributes.organization", "Please specify your organization."));
        }

        if (getCACUsername(context) != null) {
            if (isCACRegistered(context)) {
                // CAC auth, invite code not required
                errors.add(new FormMessage(null, "Sorry, this CAC seems to already be registered."));
                context.error(Errors.INVALID_REGISTRATION);
                context.validationError(formData, errors);
            }
        } else {
            // This is not a CAC auth, we need to verify the invite code
            if (!isValidInviteCode(authenticatorConfig, inviteCode)) {
                context.getEvent().detail("invite", inviteCode);
                errors.add(new FormMessage("", "Invalid or expired registration code."));
            }
        }

        if (!isValidEmailAddress(authenticatorConfig, email)) {
            context.getEvent().detail(Details.EMAIL, email);
            errors.add(new FormMessage(RegistrationPage.FIELD_EMAIL, "Please check your email address, it seems to not be an approved domain."));
        }

        if (context.getSession().users().getUserByEmail(email, context.getRealm()) != null) {
            eventError = Errors.EMAIL_IN_USE;
            formData.remove("email");
            context.getEvent().detail("email", email);
            errors.add(new FormMessage("email", Messages.EMAIL_EXISTS));
        }

        if (errors.size() > 0) {
            context.error(eventError);
            context.validationError(formData, errors);
        } else {
            context.success();
        }

    }

}