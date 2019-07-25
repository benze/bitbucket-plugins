package org.wada.bb;

import org.apache.commons.lang3.StringUtils;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class JiraTicket {
    private final String CODE_REVIEW_URL = "https://bitbucket.wada-ama.org/projects/{0}/repos/{1}/pull-requests/{2}/commits/{3}#{4}";

    private String subject;
    private List<Comment> comments = new ArrayList<>();
    private String filePath;
    private String commitId;

    public JiraTicket(Comment comment) {
        this.subject = comment.getJiraSummary();
        this.filePath = comment.getFilePath();
        this.commitId = comment.getToHash();
        addComment(comment);
    }

    public void addComment(Comment comment) {
        comments.add(comment);
    }

    public List<Comment> getComments() {
        return comments;
    }

    /**
     * Gets all descriptions joined by two newline characters.
     * All bitbucket ``` wiki markdown replaced by {code}
     *
     * @return
     */
    public String getDescription() {
        return comments.stream().filter(s -> s != null).map(comment -> comment.getJiraDescription()).collect(Collectors.joining("\n\n")) +
                "\n\n" +
                getCodeReviewReference();
    }

    /**
     * Gets the URL for a code review
     *
     * @return
     */
    private String getCodeReviewReference() {
        Optional<Comment> comment = getComments().stream().findFirst();
        return !comment.isPresent() ? "" : MessageFormat.format("[" + CODE_REVIEW_URL + "]",
                comment.get().getProjectKey(),
                comment.get().getRepository(),
                comment.get().getPullRequestId(),
                commitId, filePath);
    }



    public IssueUpdate convertToJiraIssue(String jiraProject, String epicKey) {
        return new IssueUpdate(new JiraIssue(jiraProject, epicKey, subject, getDescription()));
    }


    /**
     * Wrapper class needed for Jira Update API JSON format
     */
    public static class IssueUpdate {
        public final JiraIssue fields;

        public IssueUpdate(JiraIssue jiraIssue) {
            fields = jiraIssue;
        }

        @Override
        public String toString() {
            return fields == null ? "NO ISSUE DEFINED" : fields.toString();
        }
    }

    /**
     * Class to structure JSON for Jira API
     */
    public static class JiraIssue {

        public Map<String, String> project = new HashMap<>();
        public String summary = "";
        public String description = "";
        public Map<String, String> issuetype = new HashMap<>();
        public String customfield_10400;

        public JiraIssue(String jiraProjectKey, String epicKey, String subject, String description) {
            project.put("key", jiraProjectKey);
            issuetype.put("name", "Code Review Task");
            summary = subject;
            this.description = description;
            if (StringUtils.isNotBlank(epicKey)) {
                customfield_10400 = epicKey;
            }
        }

        @Override
        public String toString() {
            return "JiraIssue{" +
                    "project=" + project.get("key") +
                    ", issuetype=" + issuetype.get("name") +
                    ", customfield_10400='" + customfield_10400 + '\'' +
                    ", summary='" + summary + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
    }

}
