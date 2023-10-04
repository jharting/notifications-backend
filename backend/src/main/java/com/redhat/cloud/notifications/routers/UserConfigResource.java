package com.redhat.cloud.notifications.routers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.cloud.notifications.Constants;
import com.redhat.cloud.notifications.auth.principal.rhid.RhIdPrincipal;
import com.redhat.cloud.notifications.config.FeatureFlipper;
import com.redhat.cloud.notifications.db.repositories.ApplicationRepository;
import com.redhat.cloud.notifications.db.repositories.BundleRepository;
import com.redhat.cloud.notifications.db.repositories.EmailSubscriptionRepository;
import com.redhat.cloud.notifications.db.repositories.EventTypeRepository;
import com.redhat.cloud.notifications.db.repositories.TemplateRepository;
import com.redhat.cloud.notifications.models.Application;
import com.redhat.cloud.notifications.models.Bundle;
import com.redhat.cloud.notifications.models.EmailSubscriptionType;
import com.redhat.cloud.notifications.models.EventType;
import com.redhat.cloud.notifications.models.EventTypeEmailSubscription;
import com.redhat.cloud.notifications.oapi.OApiFilter;
import com.redhat.cloud.notifications.routers.models.SettingsValueByEventTypeJsonForm;
import com.redhat.cloud.notifications.routers.models.SettingsValuesByEventType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.redhat.cloud.notifications.models.EmailSubscriptionType.INSTANT;
import static com.redhat.cloud.notifications.routers.SecurityContextUtil.getOrgId;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;

public class UserConfigResource {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    EmailSubscriptionRepository emailSubscriptionRepository;

    @Inject
    BundleRepository bundleRepository;

    @Inject
    ApplicationRepository applicationRepository;

    @Inject
    EventTypeRepository eventTypeRepository;

    @Inject
    TemplateRepository templateRepository;

    @Inject
    FeatureFlipper featureFlipper;

    @Path(Constants.API_NOTIFICATIONS_V_1_0 + "/user-config")
    public static class V1 extends UserConfigResource {

    }

    @Path(Constants.API_NOTIFICATIONS_V_2_0 + "/user-config")
    public static class V2 extends UserConfigResource {

    }

    private static String getUserName(SecurityContext sec) {
        final String userName = ((RhIdPrincipal) sec.getUserPrincipal()).getName();
        return userName;
    }

    @POST
    @Path("/notification-event-type-preference")
    @Consumes(APPLICATION_JSON)
    @Produces(TEXT_PLAIN)
    @Tag(name = OApiFilter.PRIVATE)
    @Transactional
    public Response saveSettingsByEventType(@Context SecurityContext sec, @NotNull @Valid SettingsValuesByEventType userSettings) {

        final String userName = getUserName(sec);
        final String orgId = getOrgId(sec);

        // If the instant emails are disabled, we need to check that the request
        // does not contain any subscription with EmailSubscriptionType.INSTANT.
        if (!featureFlipper.isInstantEmailsEnabled() && userSettings.bundles.values().stream()
                .flatMap(bundleSettings -> bundleSettings.applications.values().stream())
                .flatMap(appSettings -> appSettings.eventTypes.values().stream())
                .flatMap(eventTypeSettings -> eventTypeSettings.emailSubscriptionTypes.keySet().stream())
                .anyMatch(subscriptionType -> subscriptionType == INSTANT)) {
            throw new BadRequestException("Subscribing to or unsubscribing from instant emails is not supported");
        }

        // for each bundle
        userSettings.bundles.forEach((bundleName, bundleSettingsValue) ->
            // for each application
            bundleSettingsValue.applications.forEach((applicationName, applicationSettingsValue) -> {
                Application app = applicationRepository.getApplication(bundleName, applicationName);
                if (app != null) {
                    // foreach event Type
                    applicationSettingsValue.eventTypes.forEach((eventTypeName, eventTypeValue) -> {
                        Optional<EventType> eventType = eventTypeRepository.find(app.getId(), eventTypeName);
                        if (eventType.isPresent()) {
                            // for each email subscription
                            eventTypeValue.emailSubscriptionTypes.forEach((emailSubscriptionType, subscribed) -> {
                                if (subscribed) {
                                    emailSubscriptionRepository.subscribeEventType(
                                        orgId, userName, eventType.get().getId(), emailSubscriptionType
                                    );
                                } else {
                                    emailSubscriptionRepository.unsubscribeEventType(
                                        orgId, userName, eventType.get().getId(), emailSubscriptionType
                                    );
                                }
                            });
                        }
                    });
                }
            }));

        return Response.ok().build();
    }

    @GET
    @Path("/notification-event-type-preference")
    @Produces(APPLICATION_JSON)
    @Tag(name = OApiFilter.PRIVATE)
    public Response getSettingsSchemaByEventType(@Context SecurityContext sec) {

        final String name = getUserName(sec);
        String orgId = getOrgId(sec);

        List<EventTypeEmailSubscription> emailSubscriptions = emailSubscriptionRepository.getEmailSubscriptionsPerEventTypeForUser(orgId, name);
        SettingsValuesByEventType settingsValues = getSettingsValueForUserByEventType(emailSubscriptions, orgId);
        String jsonFormString = settingsValuesToJsonForm(settingsValues);
        Response.ResponseBuilder builder;
        builder = Response.ok(jsonFormString);
        EntityTag etag = new EntityTag(String.valueOf(jsonFormString.hashCode()));
        builder.header("ETag", etag);
        return builder.build();
    }

    @GET
    @Path("/notification-event-type-preference/{bundleName}/{applicationName}")
    @Produces(APPLICATION_JSON)
    @Tag(name = OApiFilter.PRIVATE)
    public Response getPreferencesByEventType(
        @Context SecurityContext sec, @PathParam("bundleName") String bundleName, @PathParam("applicationName") String applicationName) {

        final String name = getUserName(sec);
        String orgId = getOrgId(sec);

        SettingsValuesByEventType settingsValues = getSettingsValueForUserByEventType(orgId, name, bundleName, applicationName);
        String jsonFormString = settingsValuesToJsonForm(settingsValues, bundleName, applicationName);
        Response.ResponseBuilder builder;
        builder = Response.ok(jsonFormString);
        EntityTag etag = new EntityTag(String.valueOf(jsonFormString.hashCode()));
        builder.header("ETag", etag);
        return builder.build();
    }


    private String settingsValuesToJsonForm(SettingsValuesByEventType settingsValues, String bundleName, String applicationName) {
        final SettingsValueByEventTypeJsonForm.Application settingsValueJsonForm = SettingsValueByEventTypeJsonForm.fromSettingsValueEventTypes(settingsValues, bundleName, applicationName);
        try {
            return mapper.writeValueAsString(settingsValueJsonForm);
        } catch (JsonProcessingException jpe) {
            throw new IllegalArgumentException(
                String.format("Unable to convert '%s' to String", settingsValueJsonForm),
                jpe
            );
        }
    }

    private String settingsValuesToJsonForm(SettingsValuesByEventType settingsValues) {
        SettingsValueByEventTypeJsonForm settingsValueJsonForm = SettingsValueByEventTypeJsonForm.fromSettingsValue(settingsValues);
        try {
            return mapper.writeValueAsString(settingsValueJsonForm);
        } catch (JsonProcessingException jpe) {
            throw new IllegalArgumentException(
                String.format("Unable to convert '%s' to String", settingsValueJsonForm),
                jpe
            );
        }
    }


    private SettingsValuesByEventType getSettingsValueForUserByEventType(String orgId, String username, String bundleName, String applicationName) {
        List<EventTypeEmailSubscription> eventTypeEmailSubscriptions = emailSubscriptionRepository.getEmailSubscriptionByEventType(orgId, username, bundleName, applicationName);

        List<Application> applicationsWithForcedEmails = applicationRepository.getApplicationsWithForcedEmail(bundleRepository.getBundle(bundleName).getId(), orgId);

        SettingsValuesByEventType settingsValues = new SettingsValuesByEventType();
        Application application = applicationRepository.getApplication(bundleName, applicationName);
        List<String> mapApplicationsWithForcedEmail = applicationsWithForcedEmails.stream().map(app -> app.getName()).collect(Collectors.toList());
        addApplicationStructureDetails(settingsValues, application, mapApplicationsWithForcedEmail.contains(applicationName));

        patchWithUserPreferencesIfExists(settingsValues, eventTypeEmailSubscriptions);
        return settingsValues;
    }

    private void addApplicationStructureDetails(final SettingsValuesByEventType settingsValues, Application application, boolean withForcedEmails) {
        Bundle bundle = application.getBundle();
        SettingsValuesByEventType.ApplicationSettingsValue applicationSettingsValue = new SettingsValuesByEventType.ApplicationSettingsValue();
        applicationSettingsValue.displayName = application.getDisplayName();
        for (EventType eventType : application.getEventTypes()) {
            if (eventType.isVisible()) {
                SettingsValuesByEventType.EventTypeSettingsValue eventTypeSettingsValue = new SettingsValuesByEventType.EventTypeSettingsValue();
                eventTypeSettingsValue.displayName = eventType.getDisplayName();
                eventTypeSettingsValue.hasForcedEmail = withForcedEmails;
                for (EmailSubscriptionType emailSubscriptionType : EmailSubscriptionType.values()) {
                    if (featureFlipper.isInstantEmailsEnabled() || emailSubscriptionType != INSTANT) {
                        // TODO NOTIF-450 How do we deal with a failure here? What kind of response should be sent to the UI when the engine is down?
                        boolean supported = templateRepository.isEmailSubscriptionSupported(bundle.getName(), application.getName(), emailSubscriptionType);
                        if (supported) {
                            eventTypeSettingsValue.emailSubscriptionTypes.put(emailSubscriptionType, emailSubscriptionType.isOptOut());
                        }
                    }
                }
                if (eventTypeSettingsValue.emailSubscriptionTypes.size() > 0) {
                    applicationSettingsValue.eventTypes.put(eventType.getName(), eventTypeSettingsValue);
                }
            }
        }

        if (applicationSettingsValue.eventTypes.size() > 0) {
            settingsValues.bundles.computeIfAbsent(bundle.getName(), unused -> {
                SettingsValuesByEventType.BundleSettingsValue bundleSettingsValue = new SettingsValuesByEventType.BundleSettingsValue();
                bundleSettingsValue.displayName = bundle.getDisplayName();
                return bundleSettingsValue;
            }).applications.put(application.getName(), applicationSettingsValue);
        }
    }

    private void patchWithUserPreferencesIfExists(final SettingsValuesByEventType settingsValues, List<EventTypeEmailSubscription> emailSubscriptions) {
        for (EventTypeEmailSubscription emailSubscription : emailSubscriptions) {
            SettingsValuesByEventType.BundleSettingsValue bundleSettings = settingsValues.bundles.get(emailSubscription.getEventType().getApplication().getBundle().getName());
            if (bundleSettings != null) {
                SettingsValuesByEventType.ApplicationSettingsValue appSettings = bundleSettings.applications.get(emailSubscription.getEventType().getApplication().getName());
                if (appSettings != null) {
                    SettingsValuesByEventType.EventTypeSettingsValue eventTypeSettings = appSettings.eventTypes.get(emailSubscription.getEventType().getName());
                    if (eventTypeSettings != null) {
                        if (eventTypeSettings.emailSubscriptionTypes.containsKey(emailSubscription.getType())) {
                            eventTypeSettings.emailSubscriptionTypes.put(emailSubscription.getType(), emailSubscription.isSubscribed());
                        }
                    }
                }
            }
        }
    }

    private SettingsValuesByEventType getSettingsValueForUserByEventType(List<EventTypeEmailSubscription> emailSubscriptions, String orgId) {
        SettingsValuesByEventType settingsValues = new SettingsValuesByEventType();

        for (Bundle bundle : bundleRepository.getBundles()) {
            List<String> applicationsWithForcedEmails = applicationRepository.getApplicationsWithForcedEmail(bundle.getId(), orgId)
                    .stream().map(Application::getName).collect(Collectors.toList());
            for (Application application : bundle.getApplications()) {
                addApplicationStructureDetails(settingsValues, application, applicationsWithForcedEmails.contains(application.getName()));
            }
        }

        patchWithUserPreferencesIfExists(settingsValues, emailSubscriptions);
        return settingsValues;
    }
}
