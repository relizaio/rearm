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
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Commit;
import org.cyclonedx.model.Hash.Algorithm;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.OrganizationalContact;
import org.cyclonedx.model.OrganizationalEntity;
import org.cyclonedx.model.Pedigree;
import org.cyclonedx.model.Component.Type;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.metadata.ToolInformation;
import org.springframework.core.io.Resource;
import org.springframework.security.access.AccessDeniedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
	
	// Jackson 3: ObjectMapper is immutable — modules are registered at
	// builder time and there's no findAndRegisterModules() runtime hook
	// (auto-detection happens during JsonMapper.builder().build()).
	// WRITE_DATES_AS_TIMESTAMPS moved off SerializationFeature onto the
	// new DateTimeFeature enum, and the default flipped from on → off.
	// We rely on numeric epoch-seconds-with-nanos for ZonedDateTime in
	// JSONB columns (the metrics_revision UPDATE on rearm.releases casts
	// to numeric), so re-enable it explicitly. Same for nanosecond
	// resolution which used to default-on under Jackson 2's
	// JavaTimeModule. WRITE_DURATIONS_AS_TIMESTAMPS keeps the on-disk
	// Duration shape consistent.
	// Jackson 3's default CREATOR visibility is PUBLIC_ONLY, which hides
	// the package-private all-args constructor Lombok's @Builder produces.
	// Loosen to NON_PRIVATE so Jackson can use those ctors with parameter
	// names (Spring Boot 4 keeps `-parameters` on by default) — that's
	// what makes our many @Data @Builder DTOs round-trip without us
	// having to annotate every single one with @NoArgsConstructor.
	public static final ObjectMapper OM = JsonMapper.builder()
			.changeDefaultVisibility(vc -> vc.withCreatorVisibility(
					com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NON_PRIVATE))
			.enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
			.enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
			.build();
	
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
		// Canonicalize both sides to the VCS repository storage key form so
		// every spelling of the same repository compares equal. NB: this used
		// to compare uri1 against itself and always returned true, which left
		// the VCS mismatch check in populateSourceCodeEntryByVcsAndCommit dead.
		// Known limitation: provider-specific split forms that differ in path
		// structure (e.g. Azure DevOps 'dev.azure.com/org/proj/_git/repo' vs
		// 'ssh.dev.azure.com/v3/org/proj/repo') are not equated -- callers
		// must not treat a false result as proof of a different repository.
		return StringUtils.equalsIgnoreCase(canonicalVcsUriForComparison(uri1), canonicalVcsUriForComparison(uri2));
	}

	/**
	 * Canonicalize a VCS URI for equality comparison only (not for storage or
	 * lookup keys): strips the ssh scheme on top of what
	 * {@link #normalizeVcsUri} (http/https scheme, user@) and
	 * {@link #cleanVcsUri} (git@, trailing .git, scp-style colon) remove, and
	 * drops a trailing slash.
	 */
	private static String canonicalVcsUriForComparison(String uri) {
		if (uri == null) return null;
		String canonical = RegExUtils.replaceFirst(uri, "^ssh://", "");
		canonical = cleanVcsUri(normalizeVcsUri(canonical));
		return StringUtils.stripEnd(canonical, "/");
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

	/**
	 * Strip any trailing {@code :tag} and {@code @digest} from a container
	 * image identifier. Used when emitting CycloneDX container components
	 * so a deliverable whose stored {@code displayIdentifier} happens to
	 * already include {@code :tag} (multiple ingestion paths into ReARM
	 * leave it that way) doesn't end up doubled when the BOM emitter
	 * sets {@code component.version} separately.
	 *
	 * <p>Conservative against hostnames that include a port — colon search
	 * starts after the last forward-slash, so e.g.
	 * {@code registry.example.com:5000/foo:1.0} trims to
	 * {@code registry.example.com:5000/foo} (port preserved). Idempotent —
	 * applying twice produces the same result.
	 *
	 * @param imageName raw container display identifier
	 * @return identifier with trailing tag/digest stripped, or the input
	 *         unchanged if neither is present (or if {@code imageName} is
	 *         null/empty)
	 */
	public static String stripContainerTagAndDigest(String imageName) {
		if (imageName == null || imageName.isEmpty()) return imageName;
		String result = imageName;
		int lastSlash = result.lastIndexOf('/');
		int searchFrom = Math.max(lastSlash, 0);
		int atIdx = result.indexOf('@', searchFrom);
		if (atIdx >= 0) {
			result = result.substring(0, atIdx);
		}
		int colonIdx = result.indexOf(':', searchFrom);
		if (colonIdx >= 0) {
			result = result.substring(0, colonIdx);
		}
		return result;
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
	
	public static void augmentRootBomComponent (String orgName, org.cyclonedx.model.Component bomComponent) {
		OrganizationalEntity oe = new OrganizationalEntity();
		oe.setName(orgName);
		bomComponent.setSupplier(oe);
	}
	
	public static void setRearmBomMetadata (Bom bom, org.cyclonedx.model.Component bomComponent) {
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
		rearmComponent.setDescription("Release Governance Platform");
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
    
    public static final String REARM_CD_GROUP = "rearm-cd---ReARM CD";

    private static final String REARM_CD_PRODUCT_NAME = "ReARM CD";
    private static final String REARM_CD_PRODUCT_VERSION = "26.07.4";

    public static final String REARM_CD_HELM_NAME = "registry.relizahub.com/library/rearm-cd";
    public static final String REARM_CD_HELM_DIGEST = "6d17397b4a43772ce716ae65522a9b26f26e0c4ca9ce0b1ba7e2e4a2717e91ca";
    private static final String REARM_CD_HELM_VERSION = "0.3.22";
    private static final String REARM_CD_HELM_COMMIT = "35504f0b07bc905c3450b92318253182e7428390";
    private static final String REARM_CD_HELM_COMMIT_MESSAGE = "chore: bump helm chart version to 0.3.22 [skip ci]";

    public static final String REARM_CD_CONTAINER_DIGEST = "2cb74272852a4922a52536197e1f5c8399a87fd37afa481c43396dee91073ca2";
    public static final String REARM_CD_CONTAINER_VERSION = "26.03.48";
    public static final String REARM_CD_CONTAINER_COMMIT = "16184724dd87351839f0bcef53d01b181eebd852";
    public static final String REARM_CD_CONTAINER_COMMIT_MESSAGE = "rearm-cd: bump go deps, rearm-cli 26.07.2, kubectl 1.35.6, helm 4.2.3, kubeseal 0.38.4, base images (#5)";

    // ReARM Watcher container — sibling to rearm-cd-app under the same ReARM CD product.
    // Was missing from the hard-coded fallback prior to the 26.05.5 BOM update.
    public static final String REARM_WATCHER_CONTAINER_NAME = "registry.relizahub.com/library/rearm-watcher-app";
    public static final String REARM_WATCHER_CONTAINER_DIGEST = "90606bb8920e43bdf3ebaf90e37f8235a97f9fb874efb1e4b1df8bbda1a74756";
    public static final String REARM_WATCHER_CONTAINER_VERSION = "26.07.0";
    public static final String REARM_WATCHER_CONTAINER_COMMIT = "21ec41d85f3febcd03468ed37f7c7cfce12480d1";
    public static final String REARM_WATCHER_CONTAINER_COMMIT_MESSAGE = "watcher: rearm-cli 26.07.2, kubectl 1.35.6 (#4)";

    public static boolean isRearmCdDigest(String digest) {
    	return ("sha256:" + REARM_CD_HELM_DIGEST).equals(digest) || REARM_CD_HELM_DIGEST.equals(digest);
    }

    public static List<org.cyclonedx.model.Component> getHardCodedRearmCdComponents() {
		// Hard-coded ReARM CD product component
		org.cyclonedx.model.Component rearmCdProduct = new org.cyclonedx.model.Component();
		rearmCdProduct.setGroup(REARM_CD_GROUP);
		rearmCdProduct.setName(REARM_CD_PRODUCT_NAME);
		rearmCdProduct.setVersion(REARM_CD_PRODUCT_VERSION);
		rearmCdProduct.setType(org.cyclonedx.model.Component.Type.APPLICATION);
		org.cyclonedx.model.Property configProp = new org.cyclonedx.model.Property();
		configProp.setName("CONFIGURATION");
		configProp.setValue("default");
		rearmCdProduct.setProperties(List.of(configProp));

		// Hard-coded Reliza CD Helm chart component
		org.cyclonedx.model.Component rearmCdHelm = new org.cyclonedx.model.Component();
		rearmCdHelm.setGroup(REARM_CD_GROUP);
		rearmCdHelm.setName(REARM_CD_HELM_NAME);
		rearmCdHelm.setVersion(REARM_CD_HELM_VERSION);
		rearmCdHelm.setType(org.cyclonedx.model.Component.Type.FILE);
		rearmCdHelm.setMimeType("application/vnd.cncf.helm.config.v1+json");
		rearmCdHelm.setHashes(List.of(new org.cyclonedx.model.Hash(org.cyclonedx.model.Hash.Algorithm.SHA_256, REARM_CD_HELM_DIGEST)));
		Pedigree helmPedigree = new Pedigree();
		Commit helmCommit = new Commit();
		helmCommit.setUid(REARM_CD_HELM_COMMIT);
		helmCommit.setUrl("github.com/relizaio/rearm-cd");
		helmCommit.setMessage(REARM_CD_HELM_COMMIT_MESSAGE);
		helmPedigree.setCommits(List.of(helmCommit));
		rearmCdHelm.setPedigree(helmPedigree);

		// Hard-coded ReARM CD container component
		org.cyclonedx.model.Component rearmCdContainer = new org.cyclonedx.model.Component();
		rearmCdContainer.setGroup(REARM_CD_GROUP);
		rearmCdContainer.setName("registry.relizahub.com/library/rearm-cd-app");
		rearmCdContainer.setVersion(REARM_CD_CONTAINER_VERSION);
		rearmCdContainer.setType(org.cyclonedx.model.Component.Type.CONTAINER);
		rearmCdContainer.setHashes(List.of(new org.cyclonedx.model.Hash(org.cyclonedx.model.Hash.Algorithm.SHA_256, REARM_CD_CONTAINER_DIGEST)));
		Pedigree containerPedigree = new Pedigree();
		Commit containerCommit = new Commit();
		containerCommit.setUid(REARM_CD_CONTAINER_COMMIT);
		containerCommit.setUrl("github.com/relizaio/rearm-cd");
		containerCommit.setMessage(REARM_CD_CONTAINER_COMMIT_MESSAGE);
		containerPedigree.setCommits(List.of(containerCommit));
		rearmCdContainer.setPedigree(containerPedigree);
		org.cyclonedx.model.Property containerSafeVersionProp = new org.cyclonedx.model.Property();
		containerSafeVersionProp.setName("reliza:containerSafeVersion");
		containerSafeVersionProp.setValue(REARM_CD_CONTAINER_VERSION);
		rearmCdContainer.setProperties(List.of(containerSafeVersionProp));

		// Hard-coded ReARM Watcher container component (sibling of rearm-cd-app under the same product).
		org.cyclonedx.model.Component rearmWatcherContainer = new org.cyclonedx.model.Component();
		rearmWatcherContainer.setGroup(REARM_CD_GROUP);
		rearmWatcherContainer.setName(REARM_WATCHER_CONTAINER_NAME);
		rearmWatcherContainer.setVersion(REARM_WATCHER_CONTAINER_VERSION);
		rearmWatcherContainer.setType(org.cyclonedx.model.Component.Type.CONTAINER);
		rearmWatcherContainer.setHashes(List.of(new org.cyclonedx.model.Hash(org.cyclonedx.model.Hash.Algorithm.SHA_256, REARM_WATCHER_CONTAINER_DIGEST)));
		Pedigree watcherPedigree = new Pedigree();
		Commit watcherCommit = new Commit();
		watcherCommit.setUid(REARM_WATCHER_CONTAINER_COMMIT);
		watcherCommit.setUrl("github.com/relizaio/rearm-cd");
		watcherCommit.setMessage(REARM_WATCHER_CONTAINER_COMMIT_MESSAGE);
		watcherPedigree.setCommits(List.of(watcherCommit));
		rearmWatcherContainer.setPedigree(watcherPedigree);
		org.cyclonedx.model.Property watcherSafeVersionProp = new org.cyclonedx.model.Property();
		watcherSafeVersionProp.setName("reliza:containerSafeVersion");
		watcherSafeVersionProp.setValue(REARM_WATCHER_CONTAINER_VERSION);
		rearmWatcherContainer.setProperties(List.of(watcherSafeVersionProp));

		return List.of(rearmCdProduct, rearmCdHelm, rearmCdContainer, rearmWatcherContainer);
    }

    public static String getHardCodedRearmCdBomJson() {
		Bom bom = new Bom();
		for (org.cyclonedx.model.Component c : getHardCodedRearmCdComponents()) {
			bom.addComponent(c);
		}
		setRearmBomMetadata(bom, null);
		BomJsonGenerator generator = BomGeneratorFactory.createJson(org.cyclonedx.Version.VERSION_16, bom);
		try {
			return generator.toJsonNode().toString();
		} catch (Exception e) {
			log.error("error when generating hard-coded reliza-cd bom", e);
			return "{}";
		}
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
		
		if (ApiTypeEnum.COMPONENT == ahp.getType()) {
			componentId = ahp.getObjUuid();
			if (null != suppliedComponentId && !componentId.equals(suppliedComponentId)) {
				throw new AccessDeniedException("Component mismatch.");
			}
		} else if (ApiTypeEnum.ORGANIZATION == ahp.getType()
				|| ApiTypeEnum.ORGANIZATION_RW == ahp.getType()
				|| ApiTypeEnum.FREEFORM == ahp.getType()) {
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
		AGENT_SESSION,
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

	/**
	 * Canonical qualifier-free PURL identity used as the content-dedup and
	 * analysis-match key for findings: {@link #minimizePurl} reduced to
	 * type/namespace/name/version/subpath. Different scanners emit the same
	 * installed package under qualifier-bearing and bare purls (e.g.
	 * {@code pkg:deb/debian/systemd@257.9-1~deb13u1?arch=amd64&distro=debian-13}
	 * vs the bare {@code pkg:deb/debian/systemd@257.9-1~deb13u1}); keying on the
	 * raw purl treats those as distinct findings and double-counts the same
	 * vulnerability. On a null or unparseable purl this falls back to the raw
	 * value (or "" when null) so non-pkg locators still dedup by their own
	 * string instead of all collapsing into one null bucket.
	 */
	public static String purlIdentity(String purl) {
		String minimized = minimizePurl(purl);
		return minimized != null ? minimized : (purl != null ? purl : "");
	}

	/**
	 * Identity-bearing qualifiers retained by canonicalization, per purl type.
	 * MUST stay in sync with {@code PRESERVED_QUALIFIERS} in rebom's
	 * bomComponentExtractor.ts — rebom computes the canonical form persisted in
	 * {@code sbom_components.canonical_purl}; this mirror only re-derives it for
	 * lookups. julia/swid carry spec-required qualifiers, oci needs the registry
	 * to disambiguate the image, and Linux distro packages (apk/deb/rpm/bitnami)
	 * need {@code distro} so advisory matching can scope to the release branch
	 * (plus rpm {@code epoch}, part of RPM version identity).
	 */
	private static final Map<String, List<String>> CANONICAL_PRESERVED_QUALIFIERS = Map.of(
			"julia", List.of("uuid"),
			"swid", List.of("tag_id"),
			"oci", List.of("repository_url"),
			"apk", List.of("distro"),
			"deb", List.of("distro"),
			"rpm", List.of("distro", "epoch"),
			"bitnami", List.of("distro"));

	/**
	 * Canonicalizes a PURL by stripping the subpath and all qualifiers except the
	 * identity-bearing ones in {@link #CANONICAL_PRESERVED_QUALIFIERS}, matching
	 * the canonical-purl form computed by rebom and persisted in
	 * {@code sbom_components.canonical_purl}. Use this before looking up an
	 * sbom_components row by purl supplied from outside.
	 */
	public static String canonicalizePurl(String purl) {
		if (purl == null || purl.isEmpty() || !purl.startsWith("pkg:")) {
			return null;
		}
		try {
			PackageURL packageUrl = new PackageURL(purl);
			PackageURLBuilder builder = PackageURLBuilder.aPackageURL()
					.withType(packageUrl.getType())
					.withNamespace(packageUrl.getNamespace())
					.withName(packageUrl.getName())
					.withVersion(packageUrl.getVersion());
			List<String> preserveKeys = CANONICAL_PRESERVED_QUALIFIERS.get(packageUrl.getType());
			Map<String, String> qualifiers = packageUrl.getQualifiers();
			if (preserveKeys != null && qualifiers != null) {
				for (String key : preserveKeys) {
					String value = qualifiers.get(key);
					if (value != null) builder.withQualifier(key, value);
				}
			}
			return builder.build().toString();
		} catch (MalformedPackageURLException e) {
			log.warn("Failed to parse PURL for canonicalization: {}", purl, e);
			return null;
		}
	}
}
