package org.wada.bb;


import org.apache.commons.lang3.StringUtils;

/**
 * Model to store the comment information
 */
public class Comment {

    private int id;
    private String filePath;
    private String comment;
    private String toHash;
    private String fromHash;
    private String repository;
    private int pullRequestId;
    private String projectKey;

    /**
     *
     * @param id
     * @param filePath
     * @param comment
     * @param toHash
     * @param fromHash
     * @throws IllegalArgumentException if id or comment are null
     */
    public Comment(String projectKey, String repository, int pullRequestId, int id, String filePath, String comment, String toHash, String fromHash) {
        if( comment == null )
            throw new IllegalArgumentException("comment must not be null");

        this.repository = repository;
        this.projectKey = projectKey;
        this.pullRequestId = pullRequestId;
        this.id = id;
        this.filePath = filePath;
        this.comment = comment;
        this.toHash = toHash;
        this.fromHash = fromHash;
    }


    public int getId() {
        return id;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getRawComment() {
        return comment;
    }

    public String getToHash() {
        return toHash;
    }

    public String getFromHash() {
        return fromHash;
    }

    public String getRepository() {
        return repository;
    }

    public int getPullRequestId() {
        return pullRequestId;
    }

    public String getProjectKey() {
        return projectKey;
    }

    /**
     * Formatted with {code} instead of bitbucket ``` markdown
     * @return
     */
    public String getJiraDescription(){
        return comment.replace("```", "{code}");
    }

    /**
     * Return the first 255 characters of the description, trimmed at the first . or newline
     * @return
     */
    public String getJiraSummary(){
        String firstSentence = StringUtils.substringBefore(comment.trim(), ".");
        String firstLine = StringUtils.substringBefore( firstSentence, "\n");
        return StringUtils.truncate(firstLine.replace("```", ""), 255);
    }

}
