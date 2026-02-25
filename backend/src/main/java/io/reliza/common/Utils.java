/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Hash.Algorithm;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalContact;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.metadata.ToolInformation;
import org.springframework.core.io.Resource;
import org.springframework.security.access.AccessDeniedException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySource;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.BranchData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RelizaData;
import io.reliza.model.tea.TeaChecksumType;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class Utils {
	private Utils() {} // non-initializable class
	
	public static final ObjectMapper OM = new ObjectMapper().findAndRegisterModules();
			// .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
	
	public static final ZoneId UTC_ZONE_ID = ZoneId.of("UTC");
	public static final ZoneOffset UTC_ZONE_OFFSET = ZoneOffset.of("Z");
	
	private static final Map<String, Algorithm> hashAlgorithmMap;
	
	private static final String UUID_REGEX =
			  "[a-f0-9]{8}(?:-[a-f0-9]{4}){4}[a-f0-9]{8}";
	
	static {
		Map<String, Algorithm> algoBuilderMap = Map.of("md5", Algorithm.MD5, "sha1", Algorithm.SHA1, "sha256", Algorithm.SHA_256, "sha-256", Algorithm.SHA_256,
				"sha384", Algorithm.SHA_384, "sha-384", Algorithm.SHA_384, "sha512", Algorithm.SHA_512, "sha-512", Algorithm.SHA_512);
		hashAlgorithmMap = Collections.unmodifiableMap(algoBuilderMap);
	}
	
	public static Algorithm resolveHashAlgorithm (String hashString) {
		return hashAlgorithmMap.get(hashString);
	}
	
	
	public static UUID parseUuidFromObject (Object uuidObj) {
		UUID uuid = null;
		if (uuidObj instanceof String) {
			uuid = UUID.fromString((String) uuidObj);
		} else if (uuidObj instanceof UUID) {
			uuid = (UUID) uuidObj;
		} else {
			// TODO: add proper error handling
			throw new IllegalStateException("cannot parse uuid");
		}
		return uuid;
	}
	
	public static Map<UUID, Set<UUID>> mapSetStringToUuid (Map<String, Collection<String>> sourceMap) {
		Map<UUID, Set<UUID>> retMap = new HashMap<>();
		sourceMap.entrySet().forEach(entry -> {
			Set<UUID> targetUuidSet = entry.getValue()
											.stream()
											.map(UUID::fromString)
											.collect(Collectors.toSet());
			retMap.put(UUID.fromString(entry.getKey()), targetUuidSet);
		});
		return retMap;
	}
	
	public static Set<UUID> collectionStringToUuidSet (Collection<String> sourceCollection) {
		return sourceCollection
								.stream()
								.map(UUID::fromString)
								.collect(Collectors.toSet());
	}
	
	public static boolean isStringUuid (String uuidTest) {
		boolean isUuid = false;
		if (StringUtils.isNotEmpty(uuidTest) && uuidTest.matches(UUID_REGEX)) {
			isUuid = true;
		}
		return isUuid;
	}
	
	public static Map<String,Object> dataToRecord (RelizaData rd) {
		@SuppressWarnings("unchecked")
		Map<String,Object> recordData = Utils.OM.convertValue(rd, LinkedHashMap.class);
		return recordData;
	}
	
	public static LocalDateTime convertZonedDateTimeToLocalUtc (ZonedDateTime zdt) {
		ZonedDateTime zdtUtc = zdt.withZoneSameInstant(UTC_ZONE_ID);
		return zdtUtc.toLocalDateTime();
	}
	
	/**
	 * Note, to work, date time string must be in the iso strict format
	 * i.e. for git log use --date=iso-strict 
	 * https://www.git-scm.com/docs/git-log#Documentation/git-log.txt---dateltformatgt
	 * for bash shell use date -Iseconds
	 * @param dateTimeStr
	 * @return
	 */
	public static LocalDateTime parseTimeZonedDateToLocalDateTimeUtc (String dateTimeStr) {
		dateTimeStr = prepareDateStringForParse(dateTimeStr);
		ZonedDateTime zdt = ZonedDateTime.parse(dateTimeStr);
		return convertZonedDateTimeToLocalUtc(zdt);
	}
	
	public static Long parseTimeZonedDateToEpochSecond (String dateTimeStr) {
		dateTimeStr = prepareDateStringForParse(dateTimeStr);
		ZonedDateTime zdt = ZonedDateTime.parse(dateTimeStr);
		return zdt.toEpochSecond();
	}
	
	public static ZonedDateTime parseTimeZonedStringToDate(String dateTimeStr) {
		dateTimeStr = prepareDateStringForParse(dateTimeStr);
		return ZonedDateTime.parse(dateTimeStr);
	}
	
	public static ZonedDateTime parseGolangDateTime(String dateTimeStr){
		ZonedDateTime parsedTime = null;
		try {
			if (StringUtils.isNotEmpty(dateTimeStr)) {
				// check if we're dealing with legacy format - TODO: remove
				// go time comes as - 2021-04-21 14:50:08.90554933 +0000 UTC m=+0.001529412
				// need to remove m= part
				if (dateTimeStr.contains(" m=")) {
					String noMParamDateTimeStrArr = dateTimeStr.split(" m=")[0];
					DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.n Z z");
					parsedTime = ZonedDateTime.parse(noMParamDateTimeStrArr, formatter);
				} else {
					parsedTime = ZonedDateTime.parse(dateTimeStr);
				}
			}
		} catch (Exception e) {
			log.warn("Exception when parsing dateTimeStr = " + dateTimeStr, e);
		}
		return parsedTime;
	}
	/**
	 * Apparently some os'es - particularly DIND in GitLab CI don't send correctly formatted date time,
	 * so we will fix it here
	 * @param dateTimeStr
	 * @return
	 */
	private static String prepareDateStringForParse (String dateTimeStr) {
		// TODO handle non-utc timezones later
		return dateTimeStr.replace("+0000", "+00:00");
	}
	
	/**
	 * We'll be storing epoch time timestamps in data model
	 * @param epochSecond
	 * @return
	 */
	public static LocalDateTime constructDateObjectFromEpochSecond (Long epochSecond) {
		return LocalDateTime.ofEpochSecond(epochSecond, 0, UTC_ZONE_OFFSET);
	}
	
	/**
	 * LocalDateTime must be relative to UTC
	 * @param ldt
	 * @return
	 */
	public static Long convertLocalDateTimeToEpochSecond (LocalDateTime ldt) {
		return ldt.toEpochSecond(UTC_ZONE_OFFSET);
	}

	/**
	 * Time from jira comes in the format 2021-08-03T16:10:03.937-04:00
	 * @param dateTimeStr
	 * @return ZonedDateTime
	 */
	public static ZonedDateTime convertISO8601StringToZonedDateTime(String dateTimeStr){
		ZonedDateTime parsedTime = null;
		try {
			if (StringUtils.isNotEmpty(dateTimeStr)) {
				parsedTime = OffsetDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern( "uuuu-MM-dd'T'HH:mm:ss.SSSX" )).toZonedDateTime();
			}
		} catch (Exception e) {
			log.warn("Exception when parsing dateTimeStr = " + dateTimeStr, e);
		}
		return parsedTime;
	}

	public static boolean uriEquals(String uri1, String uri2) {
		String uri1Edited = uri1.replace("https://", "").replace("http://", ""); 
		String uri2Edited = uri1.replace("https://", "").replace("http://", "");
		return uri1Edited.equalsIgnoreCase(uri2Edited);
	}
	
	/**
	 * Normalize VCS URI by stripping scheme (https://, http://) and username.
	 * E.g., https://relizaio@dev.azure.com/path -> dev.azure.com/path
	 * Used for VCS repository lookups and component creation.
	 */
	public static String normalizeVcsUri(String uri) {
		if (uri == null) return null;
		String normalized = uri;
		// Strip scheme
		if (normalized.startsWith("https://")) {
			normalized = normalized.substring(8);
		} else if (normalized.startsWith("http://")) {
			normalized = normalized.substring(7);
		}
		// Strip username@ prefix if present
		int atIndex = normalized.indexOf('@');
		if (atIndex > 0 && atIndex < normalized.indexOf('/')) {
			normalized = normalized.substring(atIndex + 1);
		}
		return normalized;
	}
	
	public static String cleanString (String dirtyString) {
		String cleanString = dirtyString.trim();
		cleanString = cleanString.replaceFirst("\r\n$", "");
		cleanString = cleanString.replaceFirst("\n$", "");
		cleanString = cleanString.replaceFirst("\r$", "");
		return cleanString;
	}
	
	public static Optional<DigestRecord> convertDigestStringToRecord (String digestString) {
		return convertDigestStringToRecord(digestString, DigestScope.ORIGINAL_FILE);
	}
	public static Optional<DigestRecord> convertDigestStringToRecord (String digestString, DigestScope scope) {
		Optional<DigestRecord> convertedDigest = Optional.empty();
		String cleanedDigest = cleanString(digestString);
		if (StringUtils.isNotEmpty(cleanedDigest)) {
			String[] digestParts = cleanedDigest.split(":", 2);
			if (digestParts.length == 2) {
				String digestTypeString = digestParts[0];
				String digestValue = digestParts[1];
				
				TeaChecksumType checksumType = parseDigestType(digestTypeString);
				if (null != checksumType) {
					convertedDigest = Optional.of(new DigestRecord(checksumType, digestValue, scope));
				}
			}
		}
		return convertedDigest;
	}
	
	  /**
	   * Parses a digest type string (e.g., "SHA256", "sha256") to the corresponding enum value.
	   * Handles common variations and case-insensitive matching.
	   * 
	   * @param digestTypeString the digest type string to parse
	   * @return the corresponding TeaArtifactChecksumType, or null if not supported
	   */
	  public static TeaChecksumType parseDigestType(String digestTypeString) {
	    if (digestTypeString == null || digestTypeString.trim().isEmpty()) {
	      return null;
	    }
	    
	    String upperCaseType = digestTypeString.toUpperCase().trim();
	    
	    switch (upperCaseType) {
	      case "MD5":
	        return TeaChecksumType.MD5;
	      case "SHA1":
	        return TeaChecksumType.SHA_1;
	      case "SHA256":
	        return TeaChecksumType.SHA_256;
	      case "SHA384":
	        return TeaChecksumType.SHA_384;
	      case "SHA512":
	        return TeaChecksumType.SHA_512;
	      case "SHA3256":
	      case "SHA3_256":
	        return TeaChecksumType.SHA3_256;
	      case "SHA3384":
	      case "SHA3_384":
	        return TeaChecksumType.SHA3_384;
	      case "SHA3512":
	      case "SHA3_512":
	        return TeaChecksumType.SHA3_512;
	      case "BLAKE2B256":
	      case "BLAKE2B_256":
	        return TeaChecksumType.BLAKE2B_256;
	      case "BLAKE2B384":
	      case "BLAKE2B_384":
	        return TeaChecksumType.BLAKE2B_384;
	      case "BLAKE2B512":
	      case "BLAKE2B_512":
	        return TeaChecksumType.BLAKE2B_512;
	      case "BLAKE3":
	        return TeaChecksumType.BLAKE3;
	      default:
	        return null; // Unsupported digest type
	    }
	  }

	public static String getNullable(String nullable) {
		if (nullable == null || nullable.trim().isEmpty()) {
			return "";
		}
		return nullable;
	}

	public static <T> T first(T[] array) {
		return array[0];
	}
	
	public static <T> T last(T[] array) {
		return array[array.length - 1];
	}

	public static <T> T[] drop(int numberOfItemsToDrop, T[] array) {
		return drop(numberOfItemsToDrop, 0, array);
	}

	public static <T> T[] drop(int fromStart, int fromEnd, T[] array) {
		return Arrays.copyOfRange(array, fromStart, array.length - fromEnd);
	}
	
	public static String stringifyZonedDateTimeForSql (ZonedDateTime zdt) {
		return zdt.toString().split("\\[")[0];
	}

	public static String zonedDateTimeToString(ZonedDateTime dateTime, String zoneIdStr){
		String stringDate = "";
		try {
			if(null != dateTime){
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd, hh:mm a z");
				if (StringUtils.isNotEmpty(zoneIdStr)) {
					try {
						ZoneId z = ZoneId.of(zoneIdStr);
						formatter = formatter.withZone(z);
					} catch (Exception e) {
						log.warn("Could not parse zone from id string = " + zoneIdStr);
					}
				}
				stringDate = dateTime.format(formatter);
			}
		} catch (Exception e) {
			log.warn("Exception when formating ZonedDateTime", e);
		}
		return stringDate;
	}
	
	/**
	 * Strip and replace with _ characters that are illegal for docker tag
	 * @param baseVersion
	 * @return
	 */
	public static String dockerTagSafeVersion (String baseVersion) {
		// TODO: if there are new details in https://github.com/moby/moby/issues/16304, 
		// https://github.com/opencontainers/distribution-spec/issues/154, this may be reviewed
		return baseVersion.replaceAll("[^[\\w][\\w.-]{0,127}]", "_");
	}
	
	public static String cleanBranch(String branch) {
		// Handle GitHub PR refs FIRST (before generic remote stripping):
		// refs/remotes/pull/123/merge -> pull/123/merge (remote tracking of PR)
		// refs/pull/123/merge -> pull/123/merge (direct PR ref)
		branch = branch.replaceAll("refs/remotes/pull/", "pull/");
		branch = branch.replaceAll("refs/pull/", "pull/");

		branch = branch.replaceAll("refs/tags/", "tags/");
		branch = branch.replaceAll("refs/remotes/tags/", "tags/");
		// Handle Azure DevOps PR refs:
		// refs/remotes/pullrequest/123/merge -> pullrequest/123/merge
		// refs/pullrequest/123/merge -> pullrequest/123/merge
		branch = branch.replaceAll("refs/remotes/pullrequest/", "pullrequest/");
		branch = branch.replaceAll("refs/pullrequest/", "pullrequest/");
		// Handle regular remote tracking branches: refs/remotes/{remote}/ -> empty
		// Use ^refs/remotes/ to ensure we only match at the beginning and don't strip already-cleaned prefixes like tags/
		branch = branch.replaceAll("^refs/remotes/[^/]+/", "");
		branch = branch.replaceAll("refs/heads/", ""); // GitHub sends like this
		branch = branch.replaceFirst("^origin/", ""); // Jenkins may send like this
		return branch;
	}
	
	public static String cleanVcsUri (String vcsUri) {				
		vcsUri = RegExUtils.replaceFirst(vcsUri, "^git@", "");
		vcsUri = RegExUtils.replaceFirst(vcsUri, "\\.git$", "");
		vcsUri = RegExUtils.replaceFirst(vcsUri, "http(s)?://", "");
		vcsUri = RegExUtils.replaceFirst(vcsUri, ":", "/"); // only slashs, no colons
		return vcsUri;
	}
	
	/**
	 * Derive VCS repository name from URI.
	 * Handles various Git hosting providers (GitHub, GitLab, Bitbucket, Azure DevOps).
	 * E.g., https://github.com/owner/repo -> owner/repo
	 *       git@github.com:owner/repo.git -> owner/repo
	 *       https://dev.azure.com/org/project/_git/repo -> org/project/_git/repo
	 */
	public static String deriveVcsNameFromUri(String uri) {
		if (uri == null) return null;
		
		String input = uri.trim();
		
		// Strip .git ending
		input = RegExUtils.replaceFirst(input, "\\.git$", "");
		
		// Normalize SSH-like URLs (e.g., git@github.com:owner/repo) to https for parsing
		java.util.regex.Pattern sshPattern = java.util.regex.Pattern.compile("^(?:git@|ssh://git@)([^:]+):(.+)$");
		java.util.regex.Matcher sshMatcher = sshPattern.matcher(input);
		if (sshMatcher.matches()) {
			String host = sshMatcher.group(1);
			String path = sshMatcher.group(2);
			input = "https://" + host + "/" + path;
		}
		
		// Ensure a scheme for URL parsing
		if (!input.toLowerCase().startsWith("http://") && !input.toLowerCase().startsWith("https://")) {
			input = "https://" + input;
		}
		
		// Strip username from URI if present
		input = input.replaceFirst("(https?://)([^@/]+)@", "$1");
		
		try {
			java.net.URI parsedUri = java.net.URI.create(input);
			String hostname = parsedUri.getHost();
			String path = parsedUri.getPath();
			
			// Clean provider-specific path suffixes
			if (hostname.equals("bitbucket.org")) {
				int idx = path.indexOf("/src/");
				if (idx != -1) path = path.substring(0, idx);
			} else if (hostname.equals("github.com")) {
				int idx = path.indexOf("/tree/");
				if (idx != -1) path = path.substring(0, idx);
			} else if (hostname.equals("gitlab.com")) {
				int idx = path.indexOf("/-/");
				if (idx != -1) path = path.substring(0, idx);
			} else if (hostname.equals("dev.azure.com") || hostname.endsWith(".visualstudio.com")) {
				// Azure DevOps: clean paths after _git/repo (e.g., /pullrequest/123)
				int gitIdx = path.indexOf("/_git/");
				if (gitIdx != -1) {
					int afterGit = gitIdx + 6; // length of "/_git/"
					int nextSlash = path.indexOf("/", afterGit);
					if (nextSlash != -1) {
						path = path.substring(0, nextSlash);
					}
				}
			}
			
			// Remove leading slash to get the name
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			
			// URL-encode spaces to match UI behavior
			path = path.replace(" ", "%20");
			
			return path;
		} catch (IllegalArgumentException e) {
			// If URL parsing fails, return the cleaned input without scheme
			return normalizeVcsUri(uri);
		}
	}
	

	public static String linkifyCommit(String uri, String commit){
		String linkifiedCommit = commit;
		String repoPart = "";
        if (uri.toLowerCase().contains("bitbucket.org/")) {
            repoPart = uri.toLowerCase().split("bitbucket.org/")[1];
            linkifiedCommit = "https://bitbucket.org/" + repoPart + "/commits/" + commit;
        } else if (uri.toLowerCase().contains("github.com/")) {
            repoPart = uri.toLowerCase().split("github.com/")[1];
            linkifiedCommit = "https://github.com/" + repoPart + "/commit/" + commit;
        } else if (uri.toLowerCase().contains("gitlab.com/")) {
            repoPart = uri.toLowerCase().split("gitlab.com/")[1];
            linkifiedCommit = "https://gitlab.com/" + repoPart + "/-/commit/" + commit;
        } else if (StringUtils.isNotEmpty(uri)) {
        	if (!uri.endsWith("/")) uri += "/";
        	String protocol = "https";
        	String[] protocolArr = uri.split("://");
        	if (protocolArr.length > 1) {
        		protocol = protocolArr[0];
        		uri = uri.replaceFirst(protocolArr[0] + "://", "");
        	}
        	String[] credArr = uri.split("@");
        	if (credArr.length > 1) {
        		uri = uri.replaceFirst(credArr[0] + "@", "");
        	}
        	if (uri.toLowerCase().contains("azure.com")) {
        		linkifiedCommit = protocol + "://" + uri + "commit/" + commit;
        	} else {
        		linkifiedCommit = protocol + "://" + uri + commit;
        	}
        }
        return linkifiedCommit;
	}
	
	public static void setRearmBomMetadata (Bom bom, String orgName, org.cyclonedx.model.Component bomComponent) {
		Metadata bomMeta = new Metadata();
		ToolInformation rearmTool = new ToolInformation();
		org.cyclonedx.model.Component rearmComponent = new org.cyclonedx.model.Component();
		rearmComponent.setName("ReARM");
		rearmComponent.setType(Type.APPLICATION);
		rearmComponent.setGroup("io.reliza");
		OrganizationalEntity oe = new OrganizationalEntity();
		oe.setName("Reliza Incorporated");
		oe.setUrls(List.of("https://reliza.io", "https://rearmhq.com"));
		OrganizationalContact oc = new OrganizationalContact();
		oc.setName("Reliza Incorporated");
		oc.setEmail("info@reliza.io");
		oe.setContacts(List.of(oc));
		rearmComponent.setSupplier(oe);
		rearmComponent.setAuthors(List.of(oc));
		rearmComponent.setDescription("Supply Chain Evidence Store");
		ExternalReference erRelizaVcs = new ExternalReference();
		erRelizaVcs.setType(org.cyclonedx.model.ExternalReference.Type.VCS);
		erRelizaVcs.setUrl("ssh://git@github.com/relizaio/rearm.git");
		ExternalReference erRelizaUrl = new ExternalReference();
		erRelizaUrl.setType(org.cyclonedx.model.ExternalReference.Type.WEBSITE);
		erRelizaUrl.setUrl("https://rearmhq.com");
		ExternalReference erRelizaDocs = new ExternalReference();
		erRelizaDocs.setType(org.cyclonedx.model.ExternalReference.Type.DOCUMENTATION);
		erRelizaDocs.setUrl("https://docs.rearmhq.com");
		rearmComponent.setExternalReferences(List.of(erRelizaUrl, erRelizaVcs, erRelizaDocs));
		rearmTool.setComponents(List.of(rearmComponent));
		// TODO set ReARM version
		ZonedDateTime zdt = ZonedDateTime.now();
		bomMeta.setTimestamp(Date.from(zdt.toInstant()));
		if (null != bomComponent) bomMeta.setComponent(bomComponent);
		bomMeta.setToolChoice(rearmTool);
		bom.setMetadata(bomMeta);
	}
	
    public static String resolveTagByKey(String key, List<TagRecord> tags) {
    	String resolvedValue = null;
    	var foundTag = tags.stream().filter(t -> key.equals(t.key())).findFirst();
    	if (foundTag.isPresent()) resolvedValue = foundTag.get().value();
    	return resolvedValue;
    }

    public static JsonNode readJsonFromResource(Resource resource) throws IOException {
        // Ensure the resource is readable
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("Resource is not readable");
        }

        try (InputStream inputStream = resource.getInputStream()) {
            return OM.readTree(inputStream);
        }
    }

	public static UUID resolveProgrammaticComponentId (final String suppliedComponentIdStr, final AuthHeaderParse ahp) {
		UUID componentId = null;
		UUID suppliedComponentId = null;
		if (StringUtils.isNotEmpty(suppliedComponentIdStr)) {
			suppliedComponentId = UUID.fromString(suppliedComponentIdStr);
		}
		
		if (ApiTypeEnum.COMPONENT == ahp.getType() || ApiTypeEnum.VERSION_GEN == ahp.getType()) {
			componentId = ahp.getObjUuid();
			if (null != suppliedComponentId && !componentId.equals(suppliedComponentId)) {
				throw new AccessDeniedException("Component mismatch.");
			}
		} else if (ApiTypeEnum.ORGANIZATION_RW == ahp.getType()) {
			componentId = suppliedComponentId;
		}
		return componentId;
	}

	public static void addReleaseProgrammaticValidateDeliverables (final List<Map<String,Object>> deliverableList, final BranchData bd) {
		if (null != deliverableList && !deliverableList.isEmpty()) {
			for (var d : deliverableList) {
				String dBranch = (String) d.get("branch");
				if (StringUtils.isEmpty(dBranch)) {
					d.put("branch", bd.getUuid().toString());
				} else if (!UUID.fromString(dBranch).equals(bd.getUuid())) {
					throw new RuntimeException("Branch mismatch");
				}
			}
		}
	}
	
	public record UuidDiff (UUID object, ReleaseUpdateAction diffAction) {}
	
	public static List<UuidDiff> diffUuidLists (Collection<UUID> originalList, Collection<UUID> updatedList) {
		List<UuidDiff> diffResults = new LinkedList<>();
		if (null != updatedList) {
			Set<UUID> originalSet = new HashSet<>();
			if (null != originalList && !originalList.isEmpty()) originalSet.addAll(originalList);
			Set<UUID> updatedSet = new HashSet<>(updatedList);
			
			originalSet.forEach(o -> {
				if (!updatedSet.contains(o)) diffResults.add(new UuidDiff(o, ReleaseUpdateAction.REMOVED));
			});
			updatedSet.forEach(u -> {
				if (!originalSet.contains(u)) diffResults.add(new UuidDiff(u, ReleaseUpdateAction.ADDED));
			});
		}
		return diffResults;
	}
	
	public static PackageURL setVersionOnPurl (PackageURL origPurl, String version) throws RelizaException {
		try {
			var purlBuilder = PackageURLBuilder.aPackageURL()
				.withType(origPurl.getType())
				.withName(origPurl.getName())
				.withVersion(version);
			
			if (StringUtils.isNotEmpty(origPurl.getNamespace())) purlBuilder.withNamespace(origPurl.getNamespace());
			if (null != origPurl.getQualifiers() && !origPurl.getQualifiers().isEmpty()) {
				origPurl.getQualifiers().entrySet().forEach(q -> {
					purlBuilder.withQualifier(q.getKey(), q.getValue());
				});
			}
			if (StringUtils.isNotEmpty(origPurl.getSubpath())) purlBuilder.withSubpath(origPurl.getSubpath());
			return purlBuilder.build();
		} catch (MalformedPackageURLException e) {
			throw new RelizaException(e.getMessage());
		}
	}
	
	public static enum ArtifactBelongsTo {
		DELIVERABLE,
		RELEASE,
		SCE,
	}
	public static enum StripBom {
		TRUE,
		FALSE,
	}

	/**
     * Enum to control how the root component is handled during merge operations in RebomOptions.
     */
    public enum RootComponentMergeMode {
        PRESERVE_UNDER_NEW_ROOT,
        FLATTEN_UNDER_NEW_ROOT
    }

	public static boolean isSanitizedApprovalsSent (Collection<String> approvals, OrganizationData od) {
		boolean isSanitized = true;
		var knownApprovalTypes = od.getApprovalRoles().stream().map(x -> x.id()).collect(Collectors.toUnmodifiableSet());
		Iterator<String> approvalIterator = approvals.iterator();
		while (isSanitized && approvalIterator.hasNext()) {
			String approvalToCheck = approvalIterator.next();
			if (!knownApprovalTypes.contains(approvalToCheck)) isSanitized = false;
		}
		return isSanitized;
	}
	
	/**
	 * Creates a SeveritySourceDto based on vulnerability ID pattern and severity.
	 * 
	 * @param vulnId The vulnerability ID to determine the source from
	 * @param severity The vulnerability severity
	 * @return SeveritySourceDto with appropriate source (NVD for CVE-, GHSA for GHSA-, OTHER for everything else)
	 */
	public static SeveritySourceDto createSeveritySourceDto(String vulnId, VulnerabilitySeverity severity) {
		if (vulnId == null || severity == null) {
			return new SeveritySourceDto(SeveritySource.OTHER, severity != null ? severity : VulnerabilitySeverity.UNASSIGNED);
		}
		
		SeveritySource source;
		if (vulnId.startsWith("CVE-")) {
			source = SeveritySource.NVD;
		} else if (vulnId.startsWith("GHSA-")) {
			source = SeveritySource.GHSA;
		} else {
			source = SeveritySource.OTHER;
		}
		
		return new SeveritySourceDto(source, severity);
	}
	
	/**
	 * Minimizes a PURL by stripping all qualifiers while keeping the core components
	 * (type, namespace, name, version, subpath).
	 * 
	 * @param purl The PURL string to minimize
	 * @return Minimized PURL string without qualifiers, or null if input is null or invalid
	 */
	public static String minimizePurl(String purl) {
		if (purl == null || purl.isEmpty() || !purl.startsWith("pkg:")) {
			return null;
		}
		
		try {
			// Parse the PURL
			PackageURL packageUrl = new PackageURL(purl);
			
			// Build a new PURL with the same components but without qualifiers
			PackageURLBuilder builder = PackageURLBuilder.aPackageURL()
					.withType(packageUrl.getType())
					.withNamespace(packageUrl.getNamespace())
					.withName(packageUrl.getName())
					.withVersion(packageUrl.getVersion())
					.withSubpath(packageUrl.getSubpath());
			// Note: Not setting qualifiers - they will be empty
			
			return builder.build().toString();
		} catch (MalformedPackageURLException e) {
			log.warn("Failed to parse PURL for minimization: {}", purl, e);
			return null;
		}
	}
}
