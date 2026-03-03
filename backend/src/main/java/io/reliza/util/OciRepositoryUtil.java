/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.util;

import org.apache.commons.lang3.StringUtils;

/**
 * Utility class for OCI repository path operations.
 * Handles repository path construction.
 */
public class OciRepositoryUtil {
    
    /**
     * Constructs a full repository path from namespace and repository name.
     * Example: constructRepositoryPath("reliza", "downloadable-artifacts-2026-03") 
     *          -> "reliza/downloadable-artifacts-2026-03"
     * 
     * @param namespace The namespace (e.g., "reliza")
     * @param repositoryName The repository name (e.g., "downloadable-artifacts-2026-03")
     * @return Full repository path
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static String constructRepositoryPath(String namespace, String repositoryName) {
        if (StringUtils.isEmpty(namespace) || StringUtils.isEmpty(repositoryName)) {
            throw new IllegalArgumentException("Namespace and repository name cannot be null or empty. " +
                "Expected namespace (e.g., 'reliza') and repositoryName (e.g., 'downloadable-artifacts-2026-03')");
        }
        
        if (repositoryName.contains("/")) {
            throw new IllegalArgumentException("Repository name should not contain slashes: '" + repositoryName + "'. " +
                "Expected format: 'downloadable-artifacts-2026-03', not 'namespace/repo-name'. " +
                "The namespace should be passed as a separate parameter.");
        }
        
        return namespace + "/" + repositoryName;
    }
}
