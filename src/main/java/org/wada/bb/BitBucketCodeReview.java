package org.wada.bb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.SerializationUtils;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

public class BitBucketCodeReview {
    // get a static slf4j logger for the class
    protected static final Logger logger = getLogger(BitBucketCodeReview.class);
    private static Set<String> hashCache = new HashSet<>();
    private static File dataFile;

    public static void loadCache(File dataPath) {
        dataFile = new File(dataPath, BitBucketCodeReview.class.getName() + ".dat");
        // if the data file exists, load it
        try {
            if (dataFile.exists()) {
                logger.debug("Loading previous data set from {}", dataFile.getAbsolutePath());
                hashCache = (HashSet<String>) SerializationUtils.deserialize(FileCopyUtils.copyToByteArray(dataFile));
                logger.debug("Successfully loaded {} entries", hashCache.size());
            }
        } catch (IOException e) {
            logger.warn("Cannot load previous data from {}.  Continuing without it.", dataFile.getName());
        }
    }

    public static void saveCache() {
        if (dataFile != null) {
            try {
                FileCopyUtils.copy(SerializationUtils.serialize(hashCache), dataFile);
                dataFile = null;
            } catch (IOException e) {
                logger.error("Cannot save hash data to file {}", dataFile.getName());
            }
        }
    }

    public static void main(String[] args) throws JsonProcessingException {

        loadCache(new File("."));

        final RestTemplate bitbucketRestTemplate = new RestTemplate();
        bitbucketRestTemplate.getInterceptors().add(new BasicAuthenticationInterceptor("erbe", "MjcxMTI2Mjg2MDU2OnIsggP395a2gIpy7S6wjFbID2Lc"));

        final RestTemplate jiraRestTemplate = new RestTemplate();
        jiraRestTemplate.getInterceptors().add(new BasicAuthenticationInterceptor("eric.benzacar@wada-ama.org", "5TbjcDD8mSsKANMvC8wWF268"));


        // get list of filenames in the PR
        String response = bitbucketRestTemplate.getForObject("https://bitbucket.wada-ama.org/rest/api/1.0/projects/ADAMS/repos/ADAMS/pull-requests/668/changes?limit=1000", String.class);
        JSONObject jsonObject = new JSONObject(response);
        ArrayList<String> filenames = new ArrayList<>();
        for (Iterator it = ((JSONArray) jsonObject.get("values")).iterator(); it.hasNext(); ) {
            JSONObject obj = (JSONObject) it.next();
            obj = (JSONObject) obj.get("path");
            filenames.add((String) obj.get("toString"));
        }


        // have the full list of filenames, so retrieve comments associated with each filename
        for (String filename : filenames) {
            String md5 = DigestUtils.md5DigestAsHex(filename.getBytes());
            if (hashCache.contains(md5)) {
                // already processed this file
                continue;
            }

            String jsonResponse = bitbucketRestTemplate.getForObject("https://bitbucket.wada-ama.org/rest/api/1.0/projects/ADAMS/repos/ADAMS/pull-requests/668/comments?path=" + filename, String.class);
            JSONObject json = new JSONObject(jsonResponse);

            IssueUpdate issueUpdate = new IssueUpdate();
            JiraIssue jira = issueUpdate.fields;
            ArrayList<String> comments = new ArrayList<>();
            for (Iterator it = ((JSONArray) json.get("values")).iterator(); it.hasNext(); ) {
                JSONObject obj = (JSONObject) it.next();
                if (!obj.has("text")) {
                    continue;
                }

                String comment = (String) obj.get("text");

                // use a sanitized version of the comment for the subject
                if (StringUtils.isBlank(jira.summary))
                    jira.summary = StringUtils.truncate(comment.trim().replace("```", ""), 255);

                jira.description += (StringUtils.isBlank(jira.description) ? "" : "\n\n") + comment.replace("```", "{code}");
            }

            // check to see if there is anything in the jira issue to update
            if (StringUtils.isNotBlank(jira.summary)) {
                // append the filename
                jira.description += "\n\n" + "[https://bitbucket.wada-ama.org/projects/ADAMS/repos/adams/pull-requests/668/diff#" + filename + "]";

                // create the jiraIssue
                try {
                    jsonResponse = jiraRestTemplate.postForObject("https://wada-ama.atlassian.net/rest/api/2/issue", issueUpdate, String.class);
                } catch (Exception e) {
                    ObjectMapper mapper = new ObjectMapper();
                    logger.error("Couldn't create issue\n{}", mapper.writeValueAsString(issueUpdate));
                }
                JSONObject object = new JSONObject(jsonResponse);
                if (object.has("key")) {
                    logger.info("Created CR Task: {}", object.get("key"));
                    hashCache.add(md5);
                }
            }
        }

        // dump cache
        saveCache();

    }

    @Override
    protected void finalize() throws Throwable {
        saveCache();
        super.finalize();
    }

    static class IssueUpdate {
        public JiraIssue fields = new JiraIssue();
    }

    static class JiraIssue {
        public Map<String, String> project = new HashMap<>();
        public String summary = "";
        public String description = "";
        public Map<String, String> issuetype = new HashMap<>();
        public String customfield_10400 = "ADAPI-1868";

        public JiraIssue() {
            project.put("key", "ADAPI");
            issuetype.put("name", "Code Review Task");
        }
    }


}
