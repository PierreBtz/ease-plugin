package org.jenkinsci.plugins.apperian;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.apperian.api.ApperianApi;
import com.apperian.api.ConnectionException;
import com.apperian.api.applications.PolicyConfiguration;
import com.apperian.api.applications.Application;
import com.apperian.api.applications.WrapStatus;
import com.apperian.api.signing.SignApplicationResponse;
import com.apperian.api.signing.SigningStatus;

import hudson.model.TaskListener;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.remoting.RoleChecker;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;

public class PublishFileCallable extends MasterToSlaveFileCallable<Boolean> implements FilePath.FileCallable<Boolean> {
    private static final long serialVersionUID = 1L;

    private final static Logger logger = Logger.getLogger(PublishFileCallable.class.getName());

    private ApperianUpload upload;
    private final TaskListener listener;
    private String apiTokenValue;

    public PublishFileCallable(ApperianUpload upload, TaskListener listener, String apiTokenValue) {
        this.upload = upload;
        this.listener = listener;
        this.apiTokenValue = apiTokenValue;
    }

    public void checkRoles(RoleChecker var1) throws SecurityException {

    }

    public Boolean invoke(File uploadFile, VirtualChannel channel) throws IOException, InterruptedException {

        try {
            upload.checkConfiguration();
        }
        catch (Exception e) {
            report("Error in the configuration. " + e.getMessage());
            return false;
        }

        String env = upload.getProdEnv();
        String customApperianUrl = upload.getCustomApperianUrl();
        String apiToken = this.apiTokenValue;

        ApperianApiFactory apperianApiFactory = new ApperianApiFactory();
        ApperianApi apperianApi = apperianApiFactory.create(env, customApperianUrl, apiToken);

        // When we publish the app we only enable it if it needs to be enabled and no signing is needed.
        boolean enableAppOnPublishing = (!upload.isSignApp()) && upload.isEnableApp();
        // When signing is needed we publish it as disabled and then after signing it we enable the app.
        boolean enableAppAfterSigning = upload.isSignApp() && upload.isEnableApp();

        // Check if policies are applied, and if so, get their configurations
        boolean policiesApplied = false;
        List<PolicyConfiguration> appliedPolicies = new ArrayList<PolicyConfiguration>();
        if (upload.getReapplyPolicies()) {
            try {
                Application application = apperianApi.getApplicationInfo(upload.getAppId());

                // If the application is not a type that cannot have policies applied, fail immediately.
                if (!application.canBeWrapped()) {
                    report("Applications of type " + application.getAppType() + " cannot be wrapped!  Failing...");
                    fail("Applications of type " + application.getAppType() + " cannot be wrapped!  Failing...");
                    return false;
                }

                policiesApplied = application.hasPoliciesApplied();
                if (policiesApplied) {
                    appliedPolicies = apperianApi.getAppliedPolicies(upload.getAppId()).getPolicyConfigurations();
                }
                else {
                    report("Application does not have policies applied, nothing to reapply.");
                }


            } catch (ConnectionException ex) {
                report("Failed to get application info.  Message %s.  Error details:  %s.", ex.getMessage(),
                        ex.getErrorDetails());
                ex.printStackTrace(getLogger());
                return false;
            }
        }

        // Upload the new application
        try {
            uploadApp(uploadFile, apperianApi, enableAppOnPublishing);
        }
        catch (ConnectionException ex) {
            logger.throwing("PublishFileCallable", "invoke", ex);
            report("General plugin problem. Message: %s. Error details: %s.", ex.getMessage(), ex.getErrorDetails());
            ex.printStackTrace(getLogger());
            return false;
        }

        // Re-apply policies if necessary
        try {
            if (upload.getReapplyPolicies() && policiesApplied) {
                    applyPolicies(apperianApi, appliedPolicies);
            }
        }
        catch (ConnectionException ex) {
            logger.throwing("PublishFileCallable", "invoke", ex);
            report("Error applying policies ot the application.  Message:  %s.  Error details:  %s.", ex.getMessage(),
                    ex.getErrorDetails());
            ex.printStackTrace(getLogger());
            return false;
        }

        // Re-sign the application
        try {
            if (upload.isSignApp()) {
                signApp(apperianApi);
            }
        }
        catch (ConnectionException ex) {
            logger.throwing("PublishFileCallable", "invoke", ex);
            report("Error signing application. Message: %s. Error details: %s.", ex.getMessage(), ex.getErrorDetails());
            ex.printStackTrace(getLogger());
            return false;
        }

        // Re-enable the application after signing if necessary
        try {
            if (enableAppAfterSigning) {
                enableApp(apperianApi);
            }
        }
        catch (ConnectionException ex) {
            logger.throwing("PublishFileCallable", "invoke", ex);
            report("Error enabling application. Message: %s. Error details: %s.", ex.getMessage(), ex.getErrorDetails());
            ex.printStackTrace(getLogger());
            return false;
        }

        return true;
    }

    private void uploadApp(File appBinary, ApperianApi apperianApi, boolean enableApp) throws ConnectionException {

        String appId = upload.getAppId();

        String appName = null;
        if (!Utils.isEmptyString(upload.getAppName())) {
            appName = upload.applyEnvVariablesFormatter(upload.getAppName());
        }

        String author = null;
        if (!Utils.isEmptyString(upload.getAuthor())) {
            author = upload.applyEnvVariablesFormatter(upload.getAuthor());
        }

        String shortDescription = null;
        if (!Utils.isEmptyString(upload.getShortDescription())) {
            shortDescription = upload.applyEnvVariablesFormatter(upload.getShortDescription());
        }

        String longDescription = null;
        if (!Utils.isEmptyString(upload.getLongDescription())) {
            longDescription = upload.applyEnvVariablesFormatter(upload.getLongDescription());
        }

        String version = null;
        if (!Utils.isEmptyString(upload.getVersion())) {
            logger.info("Version from config: " + upload.getVersion());
            version = upload.applyEnvVariablesFormatter(upload.getVersion());
            logger.info("Expanded version: " + version);
        }

        String versionNotes = null;
        if (!Utils.isEmptyString(upload.getVersionNotes())) {
            versionNotes = upload.applyEnvVariablesFormatter(upload.getVersionNotes());
        }

        report("Updating application binary. App Name: %s - Author: %s - Short Description: %s - Long Description: " +
                "%s - Version: %s, Version Notes: %s, Enabled: %b", appName, author, shortDescription, longDescription,
                version, versionNotes, enableApp);
        apperianApi.createNewVersion(appId, appBinary, appName, author, shortDescription, longDescription, version,
                versionNotes, enableApp);
    }

    private void signApp(ApperianApi apperianApi) throws ConnectionException {
        report("Signing application with credential '%s'", upload.getCredential());
        String appId = upload.getAppId();
        String credentialId = upload.getCredential();

        SignApplicationResponse response = apperianApi.signApplication(credentialId, appId);

        SigningStatus signingStatus = response.getStatus();
        String details = response.getStatusDetails();

        if (signingStatus == SigningStatus.IN_PROGRESS) {
            report("The application is being signed. Doing polling of signing status.");
        }

        long interval = 10;
        while (signingStatus == SigningStatus.IN_PROGRESS) {
            try {
                poll(interval);
            }
            catch (InterruptedException e) {
                break;
            }

            Application application = apperianApi.getApplicationInfo(appId);
            if (application == null || application.getVersion() == null) {
                throw new RuntimeException("Failed to get application " + appId + " signigng status");
            }

            signingStatus = application.getVersion().getStatus();
            details = getStatusDetails(application);
        }

        if (signingStatus == SigningStatus.SIGNED) {
            report(details);
        }
        else {
            fail("Error signing the application: " + details);
        }
    }

    private void applyPolicies(ApperianApi apperianApi, List<PolicyConfiguration> policyConfigs) throws ConnectionException {
        // Get the policies applied
        String appId = upload.getAppId();

        report("Attempting to apply " + policyConfigs.size() +" policies to app:  " + appId);

        if (policyConfigs.size() > 0) {
            // Apply new policies
            report("Calling applyPolicies to apply policies....");
            apperianApi.applyPolicies(appId, policyConfigs);

            // Poll while waiting for a response...
            report("Getting wrapStatus...");
            Application application = apperianApi.getApplicationInfo(appId);
            WrapStatus wrapStatus = application.getVersion().getWrapStatus();
            report("Received wrapStatus of:  " + wrapStatus);

            long interval = 10;
            while (wrapStatus == WrapStatus.APPLYING_POLICIES) {
                report("Waiting for policies to be applied...");
                try {
                    poll(interval);
                }
                catch (InterruptedException e) {
                    break;
                }

                application = apperianApi.getApplicationInfo(appId);
                if (application == null || application.getVersion() == null) {
                    throw new RuntimeException("Failed to get application " + appId + " wrap status");
                }
                wrapStatus = application.getVersion().getWrapStatus();
            }

            if (wrapStatus == WrapStatus.POLICIES_NOT_SIGNED) {
                report("Policies applied!  Application needs to be signed.");
            }
            else {
                fail("Error wrapping the application, ended with wrap status: " + wrapStatus);
                throw new RuntimeException("Error wrapping the application, ended with wrap status: " + wrapStatus);
            }
        }
        else {
            report("No policies applied to previous version, not re-wrapping...");
        }
    }


    // Wait the specified amount of ms.
    private void poll(long interval) throws InterruptedException {
        report("Sleeping " + interval + " seconds");
        Thread.sleep(TimeUnit.SECONDS.toMillis(interval));

    }

    private String getStatusDetails(Application application) {
        SigningStatus signingStatus = application.getVersion().getStatus();
        String details = application.getVersion().getStatusDetails();

        if (!Utils.isEmptyString(details)) {
            return details;
        }

        switch (signingStatus) {
            case SIGNED: return "The application was signed successfully.";
            case CANCELLED: return "The signing request was cancelled.";
            case ERROR: return "Some error happened while signing.";
            case IN_PROGRESS: return "The application is being signed.";
        }

        return details;
    }

    private void enableApp(ApperianApi apperianApi) throws ConnectionException {
        report("Enabling application with ID '%s'", upload.getAppId());
        String appId = upload.getAppId();

        apperianApi.updateApplication(appId, true);
    }

    public ApperianUpload getUpload() {
        return upload;
    }

    public PrintStream getLogger() {
        return listener.getLogger();
    }

    private void report(String message, Object ...args) {
        getLogger().println(String.format(message, args));
    }

}
