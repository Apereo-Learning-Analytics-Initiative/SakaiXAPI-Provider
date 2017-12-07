/**
 * Copyright 2013 Unicon (R) Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.sakaiproject.lrs.expapi.impl;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;

import net.oauth.OAuthAccessor;
import net.oauth.OAuthConsumer;
import net.oauth.OAuthException;
import net.oauth.OAuthMessage;
import net.oauth.OAuthServiceProvider;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.apache.commons.lang.time.FastDateFormat;
import org.apache.commons.validator.routines.UrlValidator;
import org.azeckoski.reflectutils.transcoders.JSONTranscoder;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.entitybroker.util.http.HttpClientWrapper;
import org.sakaiproject.entitybroker.util.http.HttpRESTUtils;
import org.sakaiproject.entitybroker.util.http.HttpRESTUtils.Method;
import org.sakaiproject.entitybroker.util.http.HttpResponse;
import org.sakaiproject.event.api.LearningResourceStoreProvider;
import org.sakaiproject.event.api.LearningResourceStoreService.LRS_Statement;
import org.sakaiproject.lrs.expapi.model.LRSKeys;
import org.sakaiproject.lrs.expapi.util.StatementMapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TinCanAPI LRS provider that understands how to handle LRS statements to a TincanAPI service.
 * 
 * @author Charles Hasegawa (chasegawa @ Unicon.net)
 * @author Aaron Zeckoski (azeckoski @ unicon.net) (azeckoski @ vt.edu)
 * @author Robert Long (rlong @ unicon.net)
 */
public class TincanapiLearningResourceStoreProvider implements LearningResourceStoreProvider, LRSKeys {

    private static final Logger log = LoggerFactory.getLogger(TincanapiLearningResourceStoreProvider.class);

    private static final String apiVersion = "1.0.0";
    private static final HashMap<String, String> EMPTY_PARAMS = new HashMap<>(0);
    private static final FastDateFormat FORMATTER = DateFormatUtils.ISO_DATETIME_TIME_ZONE_FORMAT;
    private static final String TEST_CONN_MESSAGE = "{\"actor\": {\"mbox\": \"mailto:no-reply@sakaiTCAPI.com\",\"name\": \"Sakai startup connection test\",\"objectType\": \"Agent\"},\"verb\": {\"id\": \"http://adlnet.gov/expapi/verbs/interacted\",\"display\": {\"en-US\": \"interacted\"}},\"object\": {\"id\": \"http://www.example.com/tincan/activities/OyeZsHFR\",\"objectType\": \"Activity\",\"definition\": {\"name\": {\"en-US\": \"Example Activity\"}}}}";
    private static final boolean GUARANTEE_SSL = true;

    // config variables
    private String basicAuthString;
    private ServerConfigurationService serverConfigurationService;
    private String consumerKey;
    private String consumerSecret;
    private String id = "tincanapi";
    private String realm;
    private int timeout = 0;
    private String url;

    // calculated variables
    private String configPrefix;
    private HashMap<String, String> headers;
    private HttpClientWrapper httpClientWrapper;
    private JSONTranscoder jsonTranscoder;
    private OAuthServiceProvider oAuthServiceProvider;
    private OAuthAccessor oAuthAccessor;

    /**
     * @param {@link ServerConfigurationService}
     */
    public TincanapiLearningResourceStoreProvider(ServerConfigurationService configurationService) {
        this.serverConfigurationService = configurationService;
    }

    /**
     * Build up the pieces needed for getting the two-step OAuth header values
     */
    private void configForOAuth() {
        oAuthServiceProvider = new OAuthServiceProvider("notUsed", "notUsed", "notUsed");
        oAuthAccessor = new OAuthAccessor(new OAuthConsumer("notUsed", consumerKey, consumerSecret, oAuthServiceProvider));
    }

    /**
     * @return create a JSON string from the various elements of the supplied statement
     */
    private String convertLRS_StatementToJSON(LRS_Statement statement) {
        HashMap<String, Object> statementMap = new HashMap<>();

        // Actor, verb and object are required
        try {
            statementMap.put(LRSStatementKey.actor.toString(), StatementMapUtils.getActorMap(statement.getActor()));
            statementMap.put(LRSStatementKey.verb.toString(), StatementMapUtils.getVerbMap(statement.getVerb()));
            statementMap.put(LRSStatementKey.object.toString(), StatementMapUtils.getObjectMap(statement.getObject()));
        } catch (Exception e) {
            log.debug("Unable to handle supplied LRS_Statement", e);
            throw new IllegalArgumentException("Unable to handle supplied LRS_Statement.\nUnable to process Actor, Verb, or Object");
        }

        if (null != statement.getContext()) {
            statementMap.put(LRSStatementKey.context.toString(), StatementMapUtils.getContextMap(statement.getContext()));
        }

        if (null != statement.getResult()) {
            statementMap.put(LRSStatementKey.result.toString(), StatementMapUtils.getResultMap(statement.getResult()));
        }

        if (null != statement.getStored()) {
            statementMap.put(LRSStatementKey.stored.toString(), FORMATTER.format(statement.getStored()));
        }

        if (null != statement.getTimestamp()) {
            statementMap.put(LRSStatementKey.timestamp.toString(), FORMATTER.format(statement.getTimestamp()));
        }

        return jsonTranscoder.encode(statementMap, null, null);
    }

    /**
     * Shutdown the provider
     */
    public void destroy() {
        if (httpClientWrapper != null) {
            try {
                httpClientWrapper.shutdown();
            } catch (Exception e) {
                log.error("Error upon destroying TinCanAPI provider.", e);
            }

            httpClientWrapper = null;
        }

        jsonTranscoder = null;
    }

    /**
     * Parse the data from the LRS statement and handle sending the request to the configured receiver. If there is an issue in
     * sending (due to endpoint being misconfigured or unavailable), we simply log the statement.
     * 
     * @see org.sakaiproject.event.api.LearningResourceStoreProvider#handleStatement(org.sakaiproject.event.api.LearningResourceStoreService.LRS_Statement)
     */
    public void handleStatement(LRS_Statement statement) {
        String data = null;

        if (statement.isPopulated()) {
            data = convertLRS_StatementToJSON(statement);
            log.debug("LRS using populated statement: {}", data);
        } else if (statement.getRawMap() != null && !statement.getRawMap().isEmpty()) {
            data = jsonTranscoder.encode(statement.getRawMap(), null, null);
            log.debug("LRS using raw Map statement: {}", data);
        } else {
            data = statement.getRawJSON();
            log.debug("LRS using raw JSON statement: {}", data);
        }

        log.debug("LRS Attempting to handle statement: {}", statement);

        try {
            HttpResponse response = postData(data);
            if (response.getResponseCode() >= 200 && response.getResponseCode() < 300) {
                log.debug("{} LRS provider successfully sent statement data: {}", id, data);
            } else {
                log.warn("{} LRS provider failed ({} {}) sending statement data ({}) to ({}), response: {}",
                    id, response.getResponseCode(), response.getResponseMessage(), data, url, response.getResponseBody());
            }
        } catch (Exception e) {
            log.error("{} LRS provider exception: Statement was not sent.\n Statement data: {}", id, data, e);
        }
    }

    /**
     * Initialize the state of this provider, reading in the configuration.
     * 
     * @throws URISyntaxException
     * @throws IOException
     * @throws OAuthException
     */
    public void init() throws OAuthException, IOException, URISyntaxException {
        readConfig();
        // Don't allow api version to be configured... we only should be reporting it
        log.info("{} LRS provider (version {}) configured: {}", id, apiVersion, url);
        
        headers = new HashMap<>(3);
        headers.put("Content-Type", "application/json");
        headers.put("X-Experience-API-Version", apiVersion);

        if (StringUtils.isNotEmpty(basicAuthString)) {
            // Note that the SCORM example javascript 64 encoder does use + and / in their output, so we do NOT use the URL safe
            // version of this method here to match their logic
            headers.put("Authorization", "Basic " + Base64.encodeBase64String((basicAuthString).getBytes()));
        } else {
            configForOAuth();
        }

        jsonTranscoder = new JSONTranscoder(true, true, false);
        httpClientWrapper = HttpRESTUtils.makeReusableHttpClient(true, timeout, null);
        HttpResponse response;

        try {
            response = postData(TEST_CONN_MESSAGE);

            if (response.getResponseCode() == 200) {
                log.info("{} LRS provider configured and ready", id);
            } else {
                log.error("{} LRS provider not configured properly OR LRS is offline - test message failed!", id);
            }
        } catch (Exception e) {
            log.error("{} LRS provider failure while trying to contact the LRS! Initialization test failed: ", id, e);
        }

        log.info("{} LRS provider INIT complete", id);
    }

    private HttpResponse postData(String data) throws OAuthException, IOException, URISyntaxException {
        if (StringUtils.isEmpty(basicAuthString)) {
            OAuthMessage message = oAuthAccessor.newRequestMessage(OAuthMessage.POST, url, null);
            message.sign(oAuthAccessor);
            String authHeader = message.getAuthorizationHeader(realm);
            headers.put("Authorization", authHeader);
        }
        HttpResponse response = HttpRESTUtils.fireRequest(httpClientWrapper, url, Method.POST, EMPTY_PARAMS, headers, data, GUARANTEE_SSL);

        return response;
    }

    /**
     * Read the setup from the configuration. All non-empty values will overwrite any values that may have been set due to DI
     * Values are prefixed with "lrs.[id]." so that multiple versions of this provider can be instantiated based on the id.
     */
    private void readConfig() {
        if (StringUtils.isEmpty(id)) {
            throw new IllegalStateException("Invalid " + id + " for LRS provider, cannot start");
        }

        configPrefix = "lrs." + id + ".";

        String value = serverConfigurationService.getConfig(configPrefix + "url", "");
        url = StringUtils.isNotEmpty(value) ? value : url;

        // ensure the URL is valid and formatted the same way (add "/" if not on the end for example)
        UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS);

        if (!urlValidator.isValid(url)) {
            throw new IllegalStateException("Invalid " + id + " LRS provider url (" + url + "), correct the " + configPrefix + "url config value");
        } /* won't work with some LRS
        else {
            if (!url.endsWith("/")) {
                url = url + "/";
            }
        }*/

        value = serverConfigurationService.getConfig(configPrefix + "request.timeout", "");

        try {
            timeout = StringUtils.isNotEmpty(value) ? Integer.parseInt(value) : timeout; // allow setter to override
        } catch (NumberFormatException e) {
                timeout = 0;
                log.debug("{} request.timeout must be an integer value - using default setting", configPrefix, e);
        }

        // basic auth
        value = serverConfigurationService.getConfig(configPrefix + "basicAuthUserPass", "");
        basicAuthString = StringUtils.isNotEmpty(value) ? value : basicAuthString;

        // oauth fields
        value = serverConfigurationService.getConfig(configPrefix + "consumer.key", "");
        consumerKey = StringUtils.isNotEmpty(value) ? value : consumerKey;
        value = serverConfigurationService.getConfig(configPrefix + "consumer.secret", "");
        consumerSecret = StringUtils.isNotEmpty(value) ? value : consumerSecret;
        value = serverConfigurationService.getConfig(configPrefix + "realm", "");
        realm = StringUtils.isNotEmpty(value) ? value : realm;

        if (StringUtils.isEmpty(basicAuthString) && (StringUtils.isEmpty(consumerKey) || StringUtils.isEmpty(consumerSecret) || StringUtils.isEmpty(realm))) {
            throw new IllegalStateException("No authentication configured properly for LRS provider, service cannot start. Please check the configuration");
        }
    }

    public void setBasicAuthString(String authString) {
        this.basicAuthString = authString;
    }

    public void setConsumerKey(String consumerKey) {
        this.consumerKey = consumerKey;
    }

    public void setConsumerSecret(String consumerSecret) {
        this.consumerSecret = consumerSecret;
    }

    /**
     * @see org.sakaiproject.event.api.LearningResourceStoreProvider#getID()
     */
    public String getID() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public void setServerConfigurationService(ServerConfigurationService serverConfigurationService) {
        this.serverConfigurationService = serverConfigurationService;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setUrl(String url) {
        this.url = url;
    }

}
