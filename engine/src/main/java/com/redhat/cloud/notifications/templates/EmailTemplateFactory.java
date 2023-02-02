package com.redhat.cloud.notifications.templates;

import com.redhat.cloud.notifications.config.FeatureFlipper;
import com.redhat.cloud.notifications.models.EmailSubscriptionType;
import io.quarkus.qute.TemplateInstance;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class EmailTemplateFactory {

    private static final String RHOSAK = "rhosak";
    private static final String APPLICATION_SERVICES = "application-services";
    private static final String RHEL = "rhel";
    private static final String POLICIES = "policies";
    private static final String ADVISOR = "advisor";
    private static final String COMPLIANCE = "compliance";
    private static final String COST_MANAGEMENT = "cost-management";
    private static final String DRIFT = "drift";
    private static final String OPENSHIFT = "openshift";
    private static final String CONSOLE = "console";
    private static final String INTEGRATIONS = "integrations";
    private static final String RBAC = "rbac";
    private static final String SOURCES = "sources";
    private static final String VULNERABILITY = "vulnerability";
    private static final String EDGE_MANAGEMENT = "edge-management";
    private static final String PATCH = "patch";
    private static final String MALWARE_DETECTION = "malware-detection";
    private static final String INVENTORY = "inventory";
    private static final String RESOURCE_OPTIMIZATION = "resource-optimization";

    private static final String BUNDLE_ANSIBLE = "ansible";
    private static final String APP_ANSIBLE_REPORTS = "reports";

    @Inject
    FeatureFlipper featureFlipper;

    @Inject
    Policies policies;

    @Inject
    Compliance compliance;

    @Inject
    Ansible ansible;

    @Inject
    CostManagement costManagement;

    @Inject
    Patch patch;

    @Inject
    Integrations integrations;

    @Inject
    Drift drift;

    @Inject
    Sources sources;

    @Inject
    Inventory inventory;

    public EmailTemplate get(String bundle, String application) {
        if (featureFlipper.isUseDefaultTemplate()) {
            return new Default(this.getInternal(bundle, application));
        }

        return this.getInternal(bundle, application);
    }

    private EmailTemplate getInternal(String bundle, String application) {
        if (bundle.equalsIgnoreCase(RHEL)) {
            switch (application.toLowerCase()) {
                case POLICIES:
                    return policies;
                case ADVISOR:
                    return new Advisor();
                case COMPLIANCE:
                    return compliance;
                case DRIFT:
                    return drift;
                case VULNERABILITY:
                    return new Vulnerability();
                case EDGE_MANAGEMENT:
                    return new EdgeManagement();
                case PATCH:
                    return patch;
                case MALWARE_DETECTION:
                    return new MalwareDetection();
                case INVENTORY:
                    return inventory;
                case RESOURCE_OPTIMIZATION:
                    return new ResourceOptimization();
                default:
                    break;
            }
        } else if (bundle.equalsIgnoreCase(OPENSHIFT)) {
            if (application.equalsIgnoreCase(ADVISOR)) {
                return new AdvisorOpenshift();
            } else if (application.equalsIgnoreCase(COST_MANAGEMENT)) {
                return costManagement;
            }
        } else if (bundle.equalsIgnoreCase(APPLICATION_SERVICES)) {
            if (application.equalsIgnoreCase(RHOSAK)) {
                return new Rhosak();
            }
        } else if (bundle.equalsIgnoreCase(BUNDLE_ANSIBLE)) {
            if (application.equalsIgnoreCase(APP_ANSIBLE_REPORTS)) {
                return ansible;
            }
        } else if (bundle.equalsIgnoreCase(CONSOLE)) {
            if (application.equalsIgnoreCase(INTEGRATIONS)) {
                return integrations;
            } else if (application.equalsIgnoreCase(SOURCES)) {
                return sources;
            }
            if (application.equalsIgnoreCase(RBAC)) {
                return new Rbac();
            }
        }

        return new EmailTemplateNotSupported();
    }
}

class EmailTemplateNotSupported implements EmailTemplate {
    @Override
    public TemplateInstance getBody(String eventType, EmailSubscriptionType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TemplateInstance getTitle(String eventType, EmailSubscriptionType type) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSupported(String eventType, EmailSubscriptionType type) {
        return false;
    }

    @Override
    public boolean isEmailSubscriptionSupported(EmailSubscriptionType type) {
        return false;
    }
}
