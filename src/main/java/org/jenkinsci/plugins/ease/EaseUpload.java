package org.jenkinsci.plugins.ease;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.*;

import com.apperian.api.ApperianEndpoint;
import com.apperian.api.EASEEndpoint;
import hudson.FilePath;
import hudson.util.Function1;
import hudson.util.Secret;
import org.jenkinsci.plugins.api.ApperianEaseEndpoint;

public class EaseUpload implements Serializable {
    private String url; // for backward compatibility
    private String accountRegion;
    private String customEaseUrl;
    private String customApperianUrl;
    private String appId;
    private String filename;
    private String username;
    private String password;
    private String metadataAssignment;
    private FilePath filePath;
    private boolean sign;
    private String credentials;
    private boolean enable;

    // @DataBoundConstructor should include all fields

    public EaseUpload(String _url,
                      String _accountRegion,
                      String _customEaseUrl,
                      String _customApperianUrl,
                      String _username,
                      String _password) {
        this.accountRegion = Utils.trim(_accountRegion);
        this.customEaseUrl = Utils.trim(_customEaseUrl);
        this.customApperianUrl = Utils.trim(_customApperianUrl);

        backwardCompatibilityHack(Utils.trim(_url));

        this.username = Utils.trim(_username);
        this.password = _password;
    }

    public EaseUpload setOtherParams(String _appId,
                                     String _filename,
                                     String _metadataAssignment,
                                     boolean _sign,
                                     String _credentials,
                                     boolean _enable) {
        this.appId = Utils.trim(_appId);
        this.filename = Utils.trim(_filename);
        this.metadataAssignment = Utils.trim(_metadataAssignment);
        this.sign = _sign;
        this.credentials = _credentials;
        this.enable = _enable;
        return this;
    }

    private void backwardCompatibilityHack(String _url) {
        if (_url.isEmpty()) {
            return;
        }
        if (!accountRegion.isEmpty()) {
            return;
        }
        if (!customApperianUrl.isEmpty()) {
            return;
        }
        if (!customEaseUrl.isEmpty()) {
            return;
        }

        if (_url.equals("")) {

        }
    }

    public EaseUpload derive(EaseUpload additionalUpload) {
        // was removed as a user story:
        // As a Jenkins plugin user, I would like to remove the ability to add additional uploads.
        throw new UnsupportedOperationException("no additional uploads support");
//        return new EaseUpload(
//                Utils.override(additionalUpload.url, url),
//                Utils.override(additionalUpload.username, username),
//                Utils.override(additionalUpload.password, password),
//                additionalUpload.getAppId(),
//                additionalUpload.getFilename(),
//                Utils.override(additionalUpload.metadataAssignment, metadataAssignment));
    }

    public FilePath getFilePath() {
        return filePath;
    }

    public String getUrl() {
        return url;
    }

    public String getAppId() {
        return appId;
    }

    public String getFilename() {
        return filename;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getMetadataAssignment() {
        return metadataAssignment;
    }

    public boolean isSign() {
        return sign;
    }

    public String getCredentials() {
        return credentials;
    }

    public boolean isEnable() {
        return enable;
    }

    public boolean checkOk() {
        return !Utils.isEmptyString(appId) &&
                checkHasFieldsForAuth() &&
                !Utils.isEmptyString(filename);
    }

    public void expand(Function1<String, String> expandVars) {
        url = expandVars.call(url);
        appId = expandVars.call(appId);
        filename = expandVars.call(filename);
        username = expandVars.call(username);
        Map<String, String> map = Utils.parseAssignmentMap(metadataAssignment);
        map = expandValuesOnly(map, expandVars);
        metadataAssignment = Utils.outAssignmentMap(map);
    }

    private Map<String, String> expandValuesOnly(Map<String, String> map,
                                                 Function1<String, String> expandVars) {
        Map<String, String> resultMap = new LinkedHashMap<>(map.size());
        for (String key : map.keySet()) {
            String val = map.get(key);
            String expandedVal = expandVars.call(val);
            resultMap.put(key, expandedVal);
        }

        return resultMap;
    }

    public boolean searchWorkspace(FilePath workspacePath,
                                   PrintStream buildLog) throws IOException, InterruptedException {
        String filename = getFilename();
        FilePath[] paths = workspacePath.list(filename);
        if (paths.length != 1) {
            buildLog.println("Found " + (paths.length == 0 ? "no files" : " ambiguous list " + Arrays.asList(paths)) +
                    " as candidates for pattern '" + filename + "'");
            return false;
        }

        this.filePath = paths[0];
        return true;
    }

    public boolean checkHasFieldsForAuth() {
        if (accountRegion == null) {
            return false;
        }

        Region region = Region.fromNameOrCustom(accountRegion);

        if (region == Region.CUSTOM) {
            return !Utils.isEmptyString(customApperianUrl) &&
                    !Utils.isEmptyString(customEaseUrl);
        }

        return true;
    }


    public EASEEndpoint createEaseEndpoint() {
        Region region = Region.fromNameOrCustom(accountRegion);
        if (region == Region.CUSTOM) {
            return new EASEEndpoint(customEaseUrl);
        } else {
            return new EASEEndpoint(region.easeUrl);
        }
    }

    public ApperianEndpoint createApperianEndpoint() {
        Region region = Region.fromNameOrCustom(accountRegion);
        if (region == Region.CUSTOM) {
            return new ApperianEndpoint(customApperianUrl);
        } else {
            return new ApperianEndpoint(region.apperianUrl);
        }
    }

    public ApperianEaseEndpoint createEndpoint() {
        return new ApperianEaseEndpoint(createEaseEndpoint(), createApperianEndpoint());
    }

    public EaseCredentials createCredentials() {
        return new EaseCredentials(username, Secret.fromString(password));
    }

    public ApperianEaseEndpoint tryAuthenticate(boolean ease, boolean apperian, StringBuilder errorMessage) {
        EaseCredentials credentials = createCredentials();
        ApperianEaseEndpoint endpoint = createEndpoint();

        try {
            credentials.lookupStoredCredentials(endpoint);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        List<String> errs = new ArrayList<>();
        if (ease) {
            EASEEndpoint easeEndpoint = endpoint.getEaseEndpoint();
            if (!credentials.authenticate(easeEndpoint)) {
                errs.add("ease: " + easeEndpoint.getLastLoginError());
            }
        }
        if (apperian) {
            ApperianEndpoint apperianEndpoint = endpoint.getApperianEndpoint();
            if (!credentials.authenticate(apperianEndpoint)) {
                errs.add("apperian: " + apperianEndpoint.getLastLoginError());
            }
        }

        if (!errs.isEmpty()) {
            if (errs.size() == 1) {
                errorMessage.append(errs.get(0));
            } else {
                errorMessage.append(errs);
            }
            return null;
        }

        return endpoint;
    }
}
