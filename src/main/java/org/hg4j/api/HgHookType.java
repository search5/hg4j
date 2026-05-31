package org.hg4j.api;

/**
 * Enum defining the types of Mercurial SCM hooks.
 */
public enum HgHookType {
    /**
     * Validation hook executed before a commit transaction (aborting allows canceling the commit)
     */
    PRE_COMMIT,
    
    /**
     * Post-processing hook executed after a commit is completed
     */
    POST_COMMIT,
    
    /**
     * Hook executed before a remote push (aborting allows canceling the push)
     */
    PRE_PUSH,
    
    /**
     * Hook executed after a push is completed
     */
    POST_PUSH,

    /**
     * Hook executed after a graft (cherry-pick) is completed
     */
    POST_GRAFT,

    /**
     * Hook executed after a rebase is completed
     */
    POST_REBASE,

    /**
     * Hook executed after a merge is completed
     */
    POST_MERGE,

    /**
     * Hook executed after an update (checkout) is completed
     */
    POST_UPDATE,

    /**
     * Validation hook executed before a merge (can be rejected)
     */
    PRE_MERGE,

    /**
     * Validation hook executed before an update (can be rejected)
     */
    PRE_UPDATE,

    /**
     * Validation hook executed before a rebase (can be rejected)
     */
    PRE_REBASE,

    /**
     * Hook executed after a strip is completed
     */
    POST_STRIP,

    /**
     * Hook executed after an amend is completed
     */
    POST_AMEND,

    /**
     * Validation hook executed before tag creation (can be rejected)
     */
    PRE_TAG,

    /**
     * Hook executed after tag creation
     */
    POST_TAG,

    /**
     * Hook executed after a changegroup is received
     */
    CHANGEGROUP
}
