/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;

import io.reliza.model.AnalysisState;
import io.reliza.model.dto.AnalyticsDtos.VulnViolationsChartDto;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseMetricsDto implements Cloneable {
	@JsonProperty private Integer critical = 0;
	@JsonProperty private Integer high = 0;
	@JsonProperty private Integer medium = 0;
	@JsonProperty private Integer low = 0;
	@JsonProperty private Integer unassigned = 0;
	@JsonProperty private Integer vulnerabilities = 0;
	@JsonProperty private Integer vulnerableComponents = 0;
	@JsonProperty private Integer components = 0;
	@JsonProperty private Integer suppressed = 0;
	@JsonProperty private Integer findingsTotal = 0;
	@JsonProperty private Integer findingsAudited = 0;
	@JsonProperty private Integer findingsUnaudited = 0;
	@JsonProperty private Integer inheritedRiskScore = 0;
	@JsonProperty private Integer policyViolationsFail = 0;
	@JsonProperty private Integer policyViolationsWarn = 0;
	@JsonProperty private Integer policyViolationsInfo = 0;
	@JsonProperty private Integer policyViolationsTotal = 0;
	@JsonProperty private Integer policyViolationsAudited = 0;
	@JsonProperty private Integer policyViolationsUnaudited = 0;
	@JsonProperty private Integer policyViolationsSecurityTotal = 0;
	@JsonProperty private Integer policyViolationsSecurityAudited = 0;
	@JsonProperty private Integer policyViolationsSecurityUnaudited = 0;
	@JsonProperty private Integer policyViolationsLicenseTotal = 0;
	@JsonProperty private Integer policyViolationsLicenseAudited = 0;
	@JsonProperty private Integer policyViolationsLicenseUnaudited = 0;
	@JsonProperty private Integer policyViolationsOperationalTotal = 0;
	@JsonProperty private Integer policyViolationsOperationalAudited = 0;
	@JsonProperty private Integer policyViolationsOperationalUnaudited = 0;
	@JsonProperty private Integer weaknesses = 0;
	@JsonProperty private ZonedDateTime lastScanned;
	@JsonProperty private List<ViolationDto> violationDetails = new LinkedList<>();
	@JsonProperty private List<VulnerabilityDto> vulnerabilityDetails = new LinkedList<>();
	@JsonProperty private List<WeaknessDto> weaknessDetails = new LinkedList<>();

	public static enum ViolationType {
		LICENSE,
		SECURITY,
		OPERATIONAL
	}
	
	public static enum VulnerabilitySeverity {
		CRITICAL,
		HIGH,
		MEDIUM,
		LOW,
		UNASSIGNED
	}

	public static record FindingSourceDto (UUID artifact, UUID release, UUID variant, AnalysisState analysisState, ZonedDateTime analysisDate) {
		public FindingSourceDto(UUID artifact, UUID release, UUID variant) {
			this(artifact, release, variant, null, null);
		}
	}
	
	public static record ViolationDto (String purl, ViolationType type, 
		@JsonProperty("license") @JsonAlias("License") String license,
		String violationDetails, Set<FindingSourceDto> sources, AnalysisState analysisState, ZonedDateTime analysisDate, ZonedDateTime attributedAt) {}
	
	public static record VulnerabilityAliasDto (VulnerabilityAliasType type, String aliasId) {}

	/**
	 * Vulnerability ID types based on OSV schema.
	 * @see <a href="https://ossf.github.io/osv-schema/">OSV Schema</a>
	 */
	public static enum VulnerabilityAliasType {
		CVE,      // CVE-YYYY-NNNNN
		GHSA,     // GHSA-xxxx-xxxx-xxxx
		GO,       // GO-YYYY-NNNN
		PYSEC,    // PYSEC-YYYY-NNN
		RUST,     // RUSTSEC-YYYY-NNNN
		OSV,      // OSV-YYYY-NNN
		DSA,      // DSA-NNNN-N (Debian)
		DLA,      // DLA-NNNN-N (Debian LTS)
		RHSA,     // RHSA-YYYY:NNNN (Red Hat)
		RHBA,     // RHBA-YYYY:NNNN (Red Hat Bug Advisory)
		RHEA,     // RHEA-YYYY:NNNN (Red Hat Enhancement Advisory)
		ALAS,     // ALAS-YYYY-NNNN (Amazon Linux)
		ALAS2,    // ALAS2-YYYY-NNNN (Amazon Linux 2)
		ALPINE,   // ALPINE-CVE-YYYY-NNNN
		CGA,      // CGA-xxxx-xxxx-xxxx (Chainguard)
		MAL,      // MAL-YYYY-NNNN (Malicious packages)
		GSD,      // GSD-YYYY-NNNNN
		OTHER
	}
	
	/**
	 * Determines the VulnerabilityAliasType based on the vulnerability ID format.
	 * @see <a href="https://ossf.github.io/osv-schema/">OSV Schema</a>
	 */
	public static VulnerabilityAliasType detectAliasType(String vulnId) {
		if (vulnId == null || vulnId.isEmpty()) {
			return VulnerabilityAliasType.OTHER;
		}
		
		if (vulnId.startsWith("CVE-")) return VulnerabilityAliasType.CVE;
		if (vulnId.startsWith("GHSA-")) return VulnerabilityAliasType.GHSA;
		if (vulnId.startsWith("GO-")) return VulnerabilityAliasType.GO;
		if (vulnId.startsWith("PYSEC-")) return VulnerabilityAliasType.PYSEC;
		if (vulnId.startsWith("RUSTSEC-")) return VulnerabilityAliasType.RUST;
		if (vulnId.startsWith("OSV-")) return VulnerabilityAliasType.OSV;
		if (vulnId.startsWith("DSA-")) return VulnerabilityAliasType.DSA;
		if (vulnId.startsWith("DLA-")) return VulnerabilityAliasType.DLA;
		if (vulnId.startsWith("RHSA-")) return VulnerabilityAliasType.RHSA;
		if (vulnId.startsWith("RHBA-")) return VulnerabilityAliasType.RHBA;
		if (vulnId.startsWith("RHEA-")) return VulnerabilityAliasType.RHEA;
		if (vulnId.startsWith("ALAS2-")) return VulnerabilityAliasType.ALAS2;
		if (vulnId.startsWith("ALAS-")) return VulnerabilityAliasType.ALAS;
		if (vulnId.startsWith("ALPINE-")) return VulnerabilityAliasType.ALPINE;
		if (vulnId.startsWith("CGA-")) return VulnerabilityAliasType.CGA;
		if (vulnId.startsWith("MAL-")) return VulnerabilityAliasType.MAL;
		if (vulnId.startsWith("GSD-")) return VulnerabilityAliasType.GSD;
		
		return VulnerabilityAliasType.OTHER;
	}

	public static enum SeveritySource {
		NVD,
		GHSA,
		ANALYSIS,
		OTHER
	}

	public static record SeveritySourceDto (SeveritySource source, VulnerabilitySeverity severity) {}

	public static record VulnerabilityDto (String purl, String vulnId, VulnerabilitySeverity severity,
		Set<VulnerabilityAliasDto> aliases, Set<FindingSourceDto> sources, Set<SeveritySourceDto> severities, AnalysisState analysisState, ZonedDateTime analysisDate, ZonedDateTime attributedAt) {}

	/**
	 * We use weaknessDto to store findngs from SARIF parsing
	 */
	public static record WeaknessDto (String cweId, String ruleId, String location,
		String fingerprint, VulnerabilitySeverity severity, Set<FindingSourceDto> sources, AnalysisState analysisState, ZonedDateTime analysisDate, ZonedDateTime attributedAt) {}
	
	@Override
    public ReleaseMetricsDto clone() {
        try {
            ReleaseMetricsDto cloned = (ReleaseMetricsDto) super.clone();
            if (null != this.violationDetails && !this.violationDetails.isEmpty()) {
            	cloned.violationDetails = new LinkedList<>(this.violationDetails);
            } else cloned.violationDetails = new LinkedList<>();
            if (null != this.vulnerabilityDetails && !this.vulnerabilityDetails.isEmpty()) {
            	cloned.vulnerabilityDetails = new LinkedList<>(this.vulnerabilityDetails);
            } else cloned.vulnerabilityDetails = new LinkedList<>();            
            return cloned;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Cloning not supported", e);
        }
    }
  
  	@Override
  	public boolean equals(Object obj) {
      if (this == obj) {
          return true;
      }

      if (obj == null || getClass() != obj.getClass()) {
          return false;
      }

      ReleaseMetricsDto otherRmd = (ReleaseMetricsDto) obj;

      // Compare each field using Objects.equals() to handle null values safely
      return Objects.equals(this.critical, otherRmd.critical) &&
             Objects.equals(this.high, otherRmd.high) &&
             Objects.equals(this.medium, otherRmd.medium) &&
             Objects.equals(this.low, otherRmd.low) &&
             Objects.equals(this.unassigned, otherRmd.unassigned) &&
             Objects.equals(this.vulnerabilities, otherRmd.vulnerabilities) &&
             Objects.equals(this.vulnerableComponents, otherRmd.vulnerableComponents) &&
             Objects.equals(this.components, otherRmd.components) &&
             Objects.equals(this.suppressed, otherRmd.suppressed) &&
             Objects.equals(this.findingsTotal, otherRmd.findingsTotal) &&
             Objects.equals(this.findingsAudited, otherRmd.findingsAudited) &&
             Objects.equals(this.findingsUnaudited, otherRmd.findingsUnaudited) &&
             Objects.equals(this.inheritedRiskScore, otherRmd.inheritedRiskScore) &&
             Objects.equals(this.policyViolationsFail, otherRmd.policyViolationsFail) &&
             Objects.equals(this.policyViolationsWarn, otherRmd.policyViolationsWarn) &&
             Objects.equals(this.policyViolationsInfo, otherRmd.policyViolationsInfo) &&
             Objects.equals(this.policyViolationsTotal, otherRmd.policyViolationsTotal) &&
             Objects.equals(this.policyViolationsAudited, otherRmd.policyViolationsAudited) &&
             Objects.equals(this.policyViolationsUnaudited, otherRmd.policyViolationsUnaudited) &&
             Objects.equals(this.policyViolationsSecurityTotal, otherRmd.policyViolationsSecurityTotal) &&
             Objects.equals(this.policyViolationsSecurityAudited, otherRmd.policyViolationsSecurityAudited) &&
             Objects.equals(this.policyViolationsSecurityUnaudited, otherRmd.policyViolationsSecurityUnaudited) &&
             Objects.equals(this.policyViolationsLicenseTotal, otherRmd.policyViolationsLicenseTotal) &&
             Objects.equals(this.policyViolationsLicenseAudited, otherRmd.policyViolationsLicenseAudited) &&
             Objects.equals(this.policyViolationsLicenseUnaudited, otherRmd.policyViolationsLicenseUnaudited) &&
             Objects.equals(this.policyViolationsOperationalTotal, otherRmd.policyViolationsOperationalTotal) &&
             Objects.equals(this.policyViolationsOperationalAudited, otherRmd.policyViolationsOperationalAudited) &&
             Objects.equals(this.policyViolationsOperationalUnaudited, otherRmd.policyViolationsOperationalUnaudited);
  	}
  	
  	@Override
  	public int hashCode() {
  	    return Objects.hash(
  	        critical, high, medium, low, unassigned, vulnerabilities,
  	        vulnerableComponents, components, suppressed, findingsTotal,
  	        findingsAudited, findingsUnaudited, inheritedRiskScore,
  	        policyViolationsFail, policyViolationsWarn, policyViolationsInfo,
  	        policyViolationsTotal, policyViolationsAudited,
  	        policyViolationsUnaudited, policyViolationsSecurityTotal,
  	        policyViolationsSecurityAudited, policyViolationsSecurityUnaudited,
  	        policyViolationsLicenseTotal, policyViolationsLicenseAudited,
  	        policyViolationsLicenseUnaudited, policyViolationsOperationalTotal,
  	        policyViolationsOperationalAudited, policyViolationsOperationalUnaudited
  	    );
  	}

  	
  	public void computeMetricsFromFacts () {
		organizeVulnerabilitiesWithAliases();
		deduplicateViolations();
		deduplicateWeaknesses();
  		int operationalViolations = 0;
  		int securityViolations = 0;
  		int licenseViolations = 0;
  		int criticalVulns = 0;
  		int highVulns = 0;
  		int mediumVulns = 0;
  		int lowVulns = 0;
  		int unassignedVulns = 0;
  		
  		for (var x: violationDetails) {
			// Skip findings with FALSE_POSITIVE or NOT_AFFECTED analysis state
			if (x.analysisState() == AnalysisState.FALSE_POSITIVE || x.analysisState() == AnalysisState.NOT_AFFECTED) {
				continue;
			}
			switch (x.type()) {
			case SECURITY:
				++securityViolations;
				break;
			case OPERATIONAL:
				++operationalViolations;
				break;
			case LICENSE:
				++licenseViolations;
				break;
			default:
				break;
			}
		}
  		
  		for (var x: vulnerabilityDetails) {
			// Skip findings with FALSE_POSITIVE or NOT_AFFECTED analysis state
			if (x.analysisState() == AnalysisState.FALSE_POSITIVE || x.analysisState() == AnalysisState.NOT_AFFECTED) {
				continue;
			}
  			switch (x.severity()) {
  			case CRITICAL:
  				++criticalVulns;
  				break;
  			case HIGH:
  				++highVulns;
  				break;
  			case MEDIUM:
  				++mediumVulns;
  				break;
  			case LOW:
  				++lowVulns;
  				break;
  			case UNASSIGNED:
  				++unassignedVulns;
  				break;
  			default:
  				break;
  			}
  		}

		int weaknessCount = 0;
		for (var x: weaknessDetails) {
			// Skip findings with FALSE_POSITIVE or NOT_AFFECTED analysis state
			if (x.analysisState() == AnalysisState.FALSE_POSITIVE || x.analysisState() == AnalysisState.NOT_AFFECTED) {
				continue;
			}
			++weaknessCount;
			switch (x.severity()) {
			case CRITICAL:
				++criticalVulns;
				break;
			case HIGH:
				++highVulns;
				break;
			case MEDIUM:
				++mediumVulns;
				break;
			case LOW:
				++lowVulns;
				break;
			case UNASSIGNED:
				++unassignedVulns;
				break;
			default:
				break;
			}
		}
  		
  		this.critical = criticalVulns;
  		this.high = highVulns;
  		this.medium = mediumVulns;
  		this.low = lowVulns;
  		this.unassigned = unassignedVulns;
  		
  		this.policyViolationsSecurityTotal = securityViolations;
  		this.policyViolationsLicenseTotal = licenseViolations;
  		this.policyViolationsOperationalTotal = operationalViolations;
  		this.policyViolationsTotal = securityViolations + licenseViolations + operationalViolations;
  		
  		this.vulnerabilities = criticalVulns + highVulns + mediumVulns + lowVulns + unassignedVulns;
  		this.weaknesses = weaknessCount;

		if (null == this.lastScanned) this.lastScanned = ZonedDateTime.now();
  	}
  	
	public void mergeWithByContent(ReleaseMetricsDto otherRmd) {
		if (null == this.violationDetails) this.violationDetails = new LinkedList<>();
		if (null == this.vulnerabilityDetails) this.vulnerabilityDetails = new LinkedList<>();
		
	    if (otherRmd != null) {
	    	this.violationDetails = mergeViolationDtos(this.violationDetails, otherRmd.violationDetails);
	    	this.vulnerabilityDetails = mergeVulnerabilityDtos(this.vulnerabilityDetails, otherRmd.vulnerabilityDetails);
	    	this.weaknessDetails = mergeWeaknessDtos(this.weaknessDetails, otherRmd.weaknessDetails);
	    	this.computeMetricsFromFacts();
	    }
	}
	
	/**
	 * Updates findings from a new source (e.g., Dependency Track) while preserving attributedAt dates.
	 * The new source is authoritative - findings not in the new source are removed.
	 * For findings that exist in both, the earlier attributedAt date is preserved.
	 * @param newMetrics The new metrics from the authoritative source
	 */
	public void updateFromAuthoritativeSource(ReleaseMetricsDto newMetrics) {
		if (newMetrics == null) {
			return;
		}
		
		// Update vulnerabilities - only keep those in newMetrics, preserve earlier attributedAt
		if (newMetrics.getVulnerabilityDetails() != null) {
			Map<String, VulnerabilityDto> existingVulnMap = new HashMap<>();
			if (this.vulnerabilityDetails != null) {
				for (VulnerabilityDto vuln : this.vulnerabilityDetails) {
					String key = getVulnerabilityKey(vuln);
					existingVulnMap.put(key, vuln);
				}
			}
			
			List<VulnerabilityDto> updatedVulnerabilities = new ArrayList<>();
			for (VulnerabilityDto newVuln : newMetrics.getVulnerabilityDetails()) {
				String key = getVulnerabilityKey(newVuln);
				VulnerabilityDto existing = existingVulnMap.get(key);
				
				if (existing != null) {
					// Preserve earlier attributedAt date
					ZonedDateTime earlierDate = selectEarlierDate(existing.attributedAt(), newVuln.attributedAt());
					updatedVulnerabilities.add(new VulnerabilityDto(
						newVuln.purl(), newVuln.vulnId(), newVuln.severity(), newVuln.aliases(),
						newVuln.sources(), newVuln.severities(), newVuln.analysisState(), newVuln.analysisDate(), earlierDate
					));
				} else {
					// New vulnerability - use as-is
					updatedVulnerabilities.add(newVuln);
				}
			}
			this.vulnerabilityDetails = updatedVulnerabilities;
		} else {
			this.vulnerabilityDetails = null;
		}
		
		// Update violations - only keep those in newMetrics, preserve earlier attributedAt
		if (newMetrics.getViolationDetails() != null) {
			Map<String, ViolationDto> existingViolationMap = new HashMap<>();
			if (this.violationDetails != null) {
				for (ViolationDto violation : this.violationDetails) {
					String key = getViolationKey(violation);
					existingViolationMap.put(key, violation);
				}
			}
			
			List<ViolationDto> updatedViolations = new ArrayList<>();
			for (ViolationDto newViolation : newMetrics.getViolationDetails()) {
				String key = getViolationKey(newViolation);
				ViolationDto existing = existingViolationMap.get(key);
				
				if (existing != null) {
					// Preserve earlier attributedAt date
					ZonedDateTime earlierDate = selectEarlierDate(existing.attributedAt(), newViolation.attributedAt());
					updatedViolations.add(new ViolationDto(
						newViolation.purl(), newViolation.type(), newViolation.license(), newViolation.violationDetails(),
						newViolation.sources(), newViolation.analysisState(), newViolation.analysisDate(), earlierDate
					));
				} else {
					// New violation - use as-is
					updatedViolations.add(newViolation);
				}
			}
			this.violationDetails = updatedViolations;
		} else {
			this.violationDetails = null;
		}
		
		// Update weaknesses - only keep those in newMetrics, preserve earlier attributedAt
		if (newMetrics.getWeaknessDetails() != null) {
			Map<String, WeaknessDto> existingWeaknessMap = new HashMap<>();
			if (this.weaknessDetails != null) {
				for (WeaknessDto weakness : this.weaknessDetails) {
					String key = weakness.fingerprint();
					if (key != null) {
						existingWeaknessMap.put(key, weakness);
					}
				}
			}
			
			List<WeaknessDto> updatedWeaknesses = new ArrayList<>();
			for (WeaknessDto newWeakness : newMetrics.getWeaknessDetails()) {
				String key = newWeakness.fingerprint();
				WeaknessDto existing = (key != null) ? existingWeaknessMap.get(key) : null;
				
				if (existing != null) {
					// Preserve earlier attributedAt date
					ZonedDateTime earlierDate = selectEarlierDate(existing.attributedAt(), newWeakness.attributedAt());
					updatedWeaknesses.add(new WeaknessDto(
						newWeakness.cweId(), newWeakness.ruleId(), newWeakness.location(), newWeakness.fingerprint(),
						newWeakness.severity(), newWeakness.sources(), newWeakness.analysisState(), newWeakness.analysisDate(), earlierDate
					));
				} else {
					// New weakness - use as-is
					updatedWeaknesses.add(newWeakness);
				}
			}
			this.weaknessDetails = updatedWeaknesses;
		} else {
			this.weaknessDetails = null;
		}
		
		// Recompute metrics from the updated facts
		this.computeMetricsFromFacts();
	}
	
	/**
	 * Sets attributedAt to the given fallback date for all findings that don't have it set.
	 * This is used for artifact-level rollup where existing artifacts without attributedAt
	 * should use the artifact creation date as the fallback.
	 * @param fallbackDate The date to use for findings without attributedAt (typically artifact creation date)
	 */
	public void setAttributedAtFallback(ZonedDateTime fallbackDate) {
		if (fallbackDate == null) {
			return;
		}
		
		// Set attributedAt for vulnerabilities that don't have it
		if (vulnerabilityDetails != null) {
			List<VulnerabilityDto> updatedVulnerabilities = new ArrayList<>();
			for (VulnerabilityDto vuln : vulnerabilityDetails) {
				if (vuln.attributedAt() == null) {
					updatedVulnerabilities.add(new VulnerabilityDto(
						vuln.purl(), vuln.vulnId(), vuln.severity(), vuln.aliases(),
						vuln.sources(), vuln.severities(), vuln.analysisState(), vuln.analysisDate(), fallbackDate
					));
				} else {
					updatedVulnerabilities.add(vuln);
				}
			}
			this.vulnerabilityDetails = updatedVulnerabilities;
		}
		
		// Set attributedAt for violations that don't have it
		if (violationDetails != null) {
			List<ViolationDto> updatedViolations = new ArrayList<>();
			for (ViolationDto violation : violationDetails) {
				if (violation.attributedAt() == null) {
					updatedViolations.add(new ViolationDto(
						violation.purl(), violation.type(), violation.license(), violation.violationDetails(),
						violation.sources(), violation.analysisState(), violation.analysisDate(), fallbackDate
					));
				} else {
					updatedViolations.add(violation);
				}
			}
			this.violationDetails = updatedViolations;
		}
		
		// Set attributedAt for weaknesses that don't have it
		if (weaknessDetails != null) {
			List<WeaknessDto> updatedWeaknesses = new ArrayList<>();
			for (WeaknessDto weakness : weaknessDetails) {
				if (weakness.attributedAt() == null) {
					updatedWeaknesses.add(new WeaknessDto(
						weakness.cweId(), weakness.ruleId(), weakness.location(), weakness.fingerprint(),
						weakness.severity(), weakness.sources(), weakness.analysisState(), weakness.analysisDate(), fallbackDate
					));
				} else {
					updatedWeaknesses.add(weakness);
				}
			}
			this.weaknessDetails = updatedWeaknesses;
		}
	}
	
	public void enrichSourcesWithRelease(UUID releaseUuid) {
		if (releaseUuid == null) {
			return;
		}
		
		// Enrich vulnerability sources with release UUID
		if (vulnerabilityDetails != null) {
			List<VulnerabilityDto> enrichedVulnerabilities = new ArrayList<>();
			for (VulnerabilityDto vuln : vulnerabilityDetails) {
				Set<FindingSourceDto> updatedSources = new LinkedHashSet<>();
				if (vuln.sources() != null && !vuln.sources().isEmpty()) {
					for (FindingSourceDto existingSource : vuln.sources()) {
						// If source is missing release UUID, enrich it with analysis state
						if (existingSource.release() == null) {
							updatedSources.add(new FindingSourceDto(existingSource.artifact(), releaseUuid, existingSource.variant(), vuln.analysisState(), vuln.analysisDate()));
						} 
						// If source has different release UUID, keep original and add new one with analysis state
						else if (!releaseUuid.equals(existingSource.release())) {
							updatedSources.add(existingSource);
							updatedSources.add(new FindingSourceDto(null, releaseUuid, null, vuln.analysisState(), vuln.analysisDate()));
						}
						// If source already has matching release UUID, update with analysis state if not set
						else {
							if (existingSource.analysisState() == null && vuln.analysisState() != null) {
								updatedSources.add(new FindingSourceDto(existingSource.artifact(), existingSource.release(), existingSource.variant(), vuln.analysisState(), vuln.analysisDate()));
							} else {
								updatedSources.add(existingSource);
							}
						}
					}
				} else {
					// Sources is null or empty - add a new source with release UUID and analysis state
					updatedSources.add(new FindingSourceDto(null, releaseUuid, null, vuln.analysisState(), vuln.analysisDate()));
				}
				
				VulnerabilityDto enrichedVuln = new VulnerabilityDto(
					vuln.purl(), 
					vuln.vulnId(), 
					vuln.severity(), 
					vuln.aliases(), 
					updatedSources,
					vuln.severities(),
					vuln.analysisState(),
					vuln.analysisDate(),
					vuln.attributedAt()
				);
				enrichedVulnerabilities.add(enrichedVuln);
			}
			this.vulnerabilityDetails = enrichedVulnerabilities;
		}
		
		// Enrich violation sources with release UUID
		if (violationDetails != null) {
			List<ViolationDto> enrichedViolations = new ArrayList<>();
			for (ViolationDto violation : violationDetails) {
				Set<FindingSourceDto> updatedSources = new LinkedHashSet<>();
				if (violation.sources() != null && !violation.sources().isEmpty()) {
					for (FindingSourceDto existingSource : violation.sources()) {
						// If source is missing release UUID, enrich it with analysis state
						if (existingSource.release() == null) {
							updatedSources.add(new FindingSourceDto(existingSource.artifact(), releaseUuid, existingSource.variant(), violation.analysisState(), violation.analysisDate()));
						} 
						// If source has different release UUID, keep original and add new one with analysis state
						else if (!releaseUuid.equals(existingSource.release())) {
							updatedSources.add(existingSource);
							updatedSources.add(new FindingSourceDto(null, releaseUuid, null, violation.analysisState(), violation.analysisDate()));
						}
						// If source already has matching release UUID, update with analysis state if not set
						else {
							if (existingSource.analysisState() == null && violation.analysisState() != null) {
								updatedSources.add(new FindingSourceDto(existingSource.artifact(), existingSource.release(), existingSource.variant(), violation.analysisState(), violation.analysisDate()));
							} else {
								updatedSources.add(existingSource);
							}
						}
					}
				} else {
					// Sources is null or empty - add a new source with release UUID and analysis state
					updatedSources.add(new FindingSourceDto(null, releaseUuid, null, violation.analysisState(), violation.analysisDate()));
				}
				
				ViolationDto enrichedViolation = new ViolationDto(
					violation.purl(), 
					violation.type(), 
					violation.license(), 
					violation.violationDetails(), 
					updatedSources,
					violation.analysisState(),
					violation.analysisDate(),
					violation.attributedAt()
				);
				enrichedViolations.add(enrichedViolation);
			}
			this.violationDetails = enrichedViolations;
		}
		
		// Enrich weakness sources with release UUID
		if (weaknessDetails != null) {
			List<WeaknessDto> enrichedWeaknesses = new ArrayList<>();
			for (WeaknessDto weakness : weaknessDetails) {
				Set<FindingSourceDto> updatedSources = new LinkedHashSet<>();
				if (weakness.sources() != null && !weakness.sources().isEmpty()) {
					for (FindingSourceDto existingSource : weakness.sources()) {
						// If source is missing release UUID, enrich it with analysis state
						if (existingSource.release() == null) {
							updatedSources.add(new FindingSourceDto(existingSource.artifact(), releaseUuid, existingSource.variant(), weakness.analysisState(), weakness.analysisDate()));
						} 
						// If source has different release UUID, keep original and add new one with analysis state
						else if (!releaseUuid.equals(existingSource.release())) {
							updatedSources.add(existingSource);
							updatedSources.add(new FindingSourceDto(null, releaseUuid, null, weakness.analysisState(), weakness.analysisDate()));
						}
						// If source already has matching release UUID, update with analysis state if not set
						else {
							if (existingSource.analysisState() == null && weakness.analysisState() != null) {
								updatedSources.add(new FindingSourceDto(existingSource.artifact(), existingSource.release(), existingSource.variant(), weakness.analysisState(), weakness.analysisDate()));
							} else {
								updatedSources.add(existingSource);
							}
						}
					}
				} else {
					// Sources is null or empty - add a new source with release UUID and analysis state
					updatedSources.add(new FindingSourceDto(null, releaseUuid, null, weakness.analysisState(), weakness.analysisDate()));
				}
				
				WeaknessDto enrichedWeakness = new WeaknessDto(
					weakness.cweId(), 
					weakness.ruleId(), 
					weakness.location(), 
					weakness.fingerprint(), 
					weakness.severity(), 
					updatedSources,
					weakness.analysisState(),
					weakness.analysisDate(),
					weakness.attributedAt()
				);
				enrichedWeaknesses.add(enrichedWeakness);
			}
			this.weaknessDetails = enrichedWeaknesses;
		}
	}
	
	private String getVulnKey (VulnerabilityDto vulnDto) {
		return vulnDto.purl + vulnDto.vulnId;
	}
	
	private List<VulnerabilityDto> mergeVulnerabilityDtos(List<VulnerabilityDto> list1, List<VulnerabilityDto> list2) {
		Map<String, VulnerabilityDto> vulnMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> getVulnKey(x), Function.identity())));
		list2.forEach(x -> {
			String xKey = getVulnKey(x);
			if (vulnMap.containsKey(xKey)) {
				// Merge sources, aliases, and severities when vulnerability already exists
				VulnerabilityDto existing = vulnMap.get(xKey);
				
				// Handle null-safe merging of sources
				Set<FindingSourceDto> combinedSources = new LinkedHashSet<>();
				if (existing.sources() != null) {
					combinedSources.addAll(existing.sources());
				}
				if (x.sources() != null) {
					combinedSources.addAll(x.sources());
				}
				
				// Handle null-safe merging of aliases
				Set<VulnerabilityAliasDto> combinedAliases = new LinkedHashSet<>();
				if (existing.aliases() != null) {
					combinedAliases.addAll(existing.aliases());
				}
				if (x.aliases() != null) {
					combinedAliases.addAll(x.aliases());
				}
				
				// Handle null-safe merging of severities
				Set<SeveritySourceDto> combinedSeverities = new LinkedHashSet<>();
				if (existing.severities() != null) {
					combinedSeverities.addAll(existing.severities());
				}
				if (x.severities() != null) {
					combinedSeverities.addAll(x.severities());
				}
				
				VulnerabilityDto merged = new VulnerabilityDto(
					existing.purl(), 
					existing.vulnId(), 
					existing.severity(), 
					combinedAliases, 
					combinedSources,
					combinedSeverities,
					existing.analysisState(),
					existing.analysisDate(),
					selectEarlierDate(existing.attributedAt(), x.attributedAt())
				);
				vulnMap.put(xKey, merged);
			} else {
				vulnMap.put(xKey, x);
			}
		});
		return new LinkedList<>(vulnMap.values());
	}
	
	private String getViolationKey (ViolationDto violationDto) {
		return violationDto.purl() + (violationDto.type() != null ? violationDto.type().name() : "");
	}
	
	private String getVulnerabilityKey(VulnerabilityDto vuln) {
		return vuln.purl() + "|" + vuln.vulnId();
	}
	
	private List<ViolationDto> mergeViolationDtos(List<ViolationDto> list1, List<ViolationDto> list2) {
		Map<String, ViolationDto> violationMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> getViolationKey(x), Function.identity())));
		list2.forEach(x -> {
			String xKey = getViolationKey(x);
			if (violationMap.containsKey(xKey)) {
				// Merge sources when violation already exists
				ViolationDto existing = violationMap.get(xKey);
				
				// Handle null-safe merging of sources
				Set<FindingSourceDto> combinedSources = new LinkedHashSet<>();
				if (existing.sources() != null) {
					combinedSources.addAll(existing.sources());
				}
				if (x.sources() != null) {
					combinedSources.addAll(x.sources());
				}
				
				ViolationDto merged = new ViolationDto(
					existing.purl(), 
					existing.type(), 
					existing.license(), 
					existing.violationDetails(), 
					combinedSources,
					existing.analysisState(),
					existing.analysisDate(),
					selectEarlierDate(existing.attributedAt(), x.attributedAt())
				);
				violationMap.put(xKey, merged);
			} else {
				violationMap.put(xKey, x);
			}
		});
		return new LinkedList<>(violationMap.values());
	}

	private List<WeaknessDto> mergeWeaknessDtos(List<WeaknessDto> list1, List<WeaknessDto> list2) {
		Map<String, WeaknessDto> vulnMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> x.fingerprint(), Function.identity())));
		list2.forEach(x -> {
			String xKey = x.fingerprint();
			if (vulnMap.containsKey(xKey)) {
				// Merge sources when weakness already exists
				WeaknessDto existing = vulnMap.get(xKey);
				
				// Handle null-safe merging of sources
				Set<FindingSourceDto> combinedSources = new LinkedHashSet<>();
				if (existing.sources() != null) {
					combinedSources.addAll(existing.sources());
				}
				if (x.sources() != null) {
					combinedSources.addAll(x.sources());
				}
				
				WeaknessDto merged = new WeaknessDto(
					existing.cweId(), 
					existing.ruleId(), 
					existing.location(), 
					existing.fingerprint(), 
					existing.severity(), 
					combinedSources,
					existing.analysisState(),
					existing.analysisDate(),
					selectEarlierDate(existing.attributedAt(), x.attributedAt())
				);
				vulnMap.put(xKey, merged);
			} else {
				vulnMap.put(xKey, x);
			}
		});
		return new LinkedList<>(vulnMap.values());
	}
	
	public void organizeVulnerabilitiesWithAliases() {
		if (vulnerabilityDetails == null || vulnerabilityDetails.isEmpty()) {
			return;
		}
		
		// Create a map to group vulnerabilities by their purl+identifier composite keys
		Map<String, List<VulnerabilityDto>> purlIdToVulnsMap = new LinkedHashMap<>();
		
		// Collect all identifiers for each vulnerability with purl prefix
		for (VulnerabilityDto vuln : vulnerabilityDetails) {
			Set<String> allIds = getAllIdentifiers(vuln);
			for (String id : allIds) {
				// Create composite key: purl + "|" + id
				String compositeKey = (vuln.purl() != null ? vuln.purl() : "") + "|" + id;
				purlIdToVulnsMap.computeIfAbsent(compositeKey, k -> new ArrayList<>()).add(vuln);
			}
		}
		
		// Find groups of vulnerabilities that share identifiers within the same purl
		Set<VulnerabilityDto> processed = new HashSet<>();
		List<VulnerabilityDto> mergedVulnerabilities = new ArrayList<>();
		
		for (VulnerabilityDto vuln : vulnerabilityDetails) {
			if (processed.contains(vuln)) {
				continue;
			}
			
			// Find all vulnerabilities that should be merged with this one (same purl + shared identifiers)
			Set<VulnerabilityDto> toMerge = new HashSet<>();
			toMerge.add(vuln);
			processed.add(vuln);
			
			// Get all identifiers for current vulnerability
			Set<String> currentIds = getAllIdentifiers(vuln);
			
			// Find all other vulnerabilities that share any identifier within the same purl
			for (String id : currentIds) {
				String compositeKey = (vuln.purl() != null ? vuln.purl() : "") + "|" + id;
				List<VulnerabilityDto> vulnsWithId = purlIdToVulnsMap.get(compositeKey);
				if (vulnsWithId != null) {
					for (VulnerabilityDto otherVuln : vulnsWithId) {
						if (!processed.contains(otherVuln) && 
							Objects.equals(vuln.purl(), otherVuln.purl())) {
							toMerge.add(otherVuln);
							processed.add(otherVuln);
						}
					}
				}
			}
			
			// Merge all vulnerabilities in the group
			VulnerabilityDto merged = mergeVulnerabilityGroup(toMerge);
			mergedVulnerabilities.add(merged);
		}
		
		// Replace the vulnerability list with merged results
		vulnerabilityDetails = mergedVulnerabilities;
	}
	
	private Set<String> getAllIdentifiers(VulnerabilityDto vuln) {
		Set<String> allIds = new HashSet<>();
		
		// Add the main vulnerability ID
		if (vuln.vulnId() != null && !vuln.vulnId().trim().isEmpty()) {
			allIds.add(vuln.vulnId().trim());
			if (vuln.vulnId().trim().startsWith("ALPINE-CVE-")) {
				String nonAlpineId = vuln.vulnId().trim().replaceFirst("ALPINE-", "");
				allIds.add(nonAlpineId);
			}
		}
		
		// Add all alias IDs
		if (vuln.aliases() != null) {
			for (VulnerabilityAliasDto alias : vuln.aliases()) {
				if (alias.aliasId() != null && !alias.aliasId().trim().isEmpty()) {
					allIds.add(alias.aliasId().trim());
				}
			}
		}
		
		return allIds;
	}
	
	private VulnerabilityDto mergeVulnerabilityGroup(Set<VulnerabilityDto> vulnerabilities) {
		if (vulnerabilities.isEmpty()) {
			return null;
		}
		
		// Select the vulnerability to use as the base for analysis state
		// Priority: 1) Has analysisState, 2) CVE- ID, 3) GHSA- ID, 4) Any
		VulnerabilityDto baseVuln = selectBaseVulnerability(vulnerabilities);
		
		if (vulnerabilities.size() == 1) {
			// Even for single vulnerabilities, ensure proper ID prioritization
			VulnerabilityDto singleVuln = vulnerabilities.iterator().next();
			Set<String> allIds = getAllIdentifiers(singleVuln);
			String bestPrimaryId = selectBestPrimaryId(allIds);
			
			// If the current primary ID is already the best, still check for alias cleanup
			if (bestPrimaryId.equals(singleVuln.vulnId())) {
				// Check if any aliases duplicate the primary ID and clean them up
				Set<VulnerabilityAliasDto> cleanedAliases = new HashSet<>();
				if (singleVuln.aliases() != null) {
					for (VulnerabilityAliasDto alias : singleVuln.aliases()) {
						// Only keep aliases that don't duplicate the primary ID
						if (alias.aliasId() != null && !alias.aliasId().trim().equals(bestPrimaryId)) {
							cleanedAliases.add(alias);
						}
					}
				}
				
				// Return with cleaned aliases if needed, otherwise return as-is
				if (singleVuln.aliases() == null || cleanedAliases.size() != singleVuln.aliases().size()) {
					return new VulnerabilityDto(singleVuln.purl(), bestPrimaryId, singleVuln.severity(), cleanedAliases, singleVuln.sources(), singleVuln.severities(), singleVuln.analysisState(), singleVuln.analysisDate(), singleVuln.attributedAt());
				} else {
					return singleVuln;
				}
			}
			
			// Otherwise, reorganize with proper primary ID
			Set<VulnerabilityAliasDto> finalAliases = new HashSet<>();
			for (String id : allIds) {
				if (!id.equals(bestPrimaryId)) {
					finalAliases.add(new VulnerabilityAliasDto(detectAliasType(id), id));
				}
			}
			
			return new VulnerabilityDto(singleVuln.purl(), bestPrimaryId, singleVuln.severity(), finalAliases, singleVuln.sources(), singleVuln.severities(), singleVuln.analysisState(), singleVuln.analysisDate(), singleVuln.attributedAt());
		}
		
		// Collect all unique identifiers and find the best primary ID (CVE preferred)
		Set<String> allIds = new HashSet<>();
		Set<FindingSourceDto> allSources = new HashSet<>();
		
		// Use the base vulnerability's purl
		String purl = baseVuln.purl();
		
		// Collect all data from all vulnerabilities
		for (VulnerabilityDto vuln : vulnerabilities) {
			// Collect main ID
			if (vuln.vulnId() != null && !vuln.vulnId().trim().isEmpty()) {
				allIds.add(vuln.vulnId().trim());
			}
			
			// Collect aliases
			if (vuln.aliases() != null) {
				for (VulnerabilityAliasDto alias : vuln.aliases()) {
					if (alias.aliasId() != null && !alias.aliasId().trim().isEmpty()) {
						allIds.add(alias.aliasId().trim());
					}
				}
			}
			
			// Collect sources
			if (vuln.sources() != null) {
				allSources.addAll(vuln.sources());
			}
		}
		
		// Find the best primary ID (CVE preferred over GHSA)
		String primaryId = selectBestPrimaryId(allIds);
		
		// Create aliases set excluding the primary ID
		Set<VulnerabilityAliasDto> finalAliases = new HashSet<>();
		for (String id : allIds) {
			if (!id.equals(primaryId)) {
				finalAliases.add(new VulnerabilityAliasDto(detectAliasType(id), id));
			}
		}
		
		// Collect all severities and determine the best primary severity
		Set<SeveritySourceDto> allSeverities = new LinkedHashSet<>();
		for (VulnerabilityDto vuln : vulnerabilities) {
			if (vuln.severities() != null) {
				allSeverities.addAll(vuln.severities());
			}
		}
		
		// Determine the best severity based on source priority
		VulnerabilitySeverity bestSeverity = selectBestSeverity(allSeverities);
		
		// Use analysis state and date from the selected base vulnerability
		// Select earliest attributedAt from all merged vulnerabilities
		ZonedDateTime earliestAttributedAt = null;
		for (VulnerabilityDto vuln : vulnerabilities) {
			earliestAttributedAt = selectEarlierDate(earliestAttributedAt, vuln.attributedAt());
		}
		
		return new VulnerabilityDto(purl, primaryId, bestSeverity, finalAliases, allSources, allSeverities, baseVuln.analysisState(), baseVuln.analysisDate(), earliestAttributedAt);
	}
	
	/**
	 * Select the base vulnerability to preserve analysis state from.
	 * Priority: 1) Has analysisState (audited), 2) CVE- ID, 3) GHSA- ID, 4) First one
	 */
	private VulnerabilityDto selectBaseVulnerability(Set<VulnerabilityDto> vulnerabilities) {
		VulnerabilityDto withAnalysis = null;
		VulnerabilityDto withCve = null;
		VulnerabilityDto withGhsa = null;
		VulnerabilityDto first = null;
		
		for (VulnerabilityDto vuln : vulnerabilities) {
			if (first == null) {
				first = vuln;
			}
			
			// Priority 1: Has analysis state
			if (vuln.analysisState() != null) {
				if (withAnalysis == null) {
					withAnalysis = vuln;
				}
			}
			
			// Priority 2: CVE ID
			if (vuln.vulnId() != null && vuln.vulnId().startsWith("CVE-")) {
				if (withCve == null) {
					withCve = vuln;
				}
			}
			
			// Priority 3: GHSA ID
			if (vuln.vulnId() != null && vuln.vulnId().startsWith("GHSA-")) {
				if (withGhsa == null) {
					withGhsa = vuln;
				}
			}
		}
		
		// Return in priority order
		if (withAnalysis != null) {
			return withAnalysis;
		}
		if (withCve != null) {
			return withCve;
		}
		if (withGhsa != null) {
			return withGhsa;
		}
		return first;
	}
	
	private String selectBestPrimaryId(Set<String> allIds) {
		// Prefer CVE IDs over GHSA IDs
		for (String id : allIds) {
			if (id.startsWith("CVE-")) {
				return id;
			}
		}
		
		// If no CVE found, return the first GHSA or any other ID
		for (String id : allIds) {
			if (id.startsWith("GHSA-")) {
				return id;
			}
		}
		
		// Return any available ID if neither CVE nor GHSA found
		return allIds.iterator().next();
	}
	
	private VulnerabilitySeverity selectBestSeverity(Set<SeveritySourceDto> severities) {
		if (severities == null || severities.isEmpty()) {
			return VulnerabilitySeverity.UNASSIGNED;
		}
		
		// Priority order: ANALYSIS > NVD (if not UNASSIGNED) > GHSA > NVD (even if UNASSIGNED) > OTHER
		// First, look for ANALYSIS severity (from VulnAnalysis)
		for (SeveritySourceDto severityDto : severities) {
			if (severityDto.source() == SeveritySource.ANALYSIS) {
				return severityDto.severity();
			}
		}
		
		// Look for NVD severity (prefer if not UNASSIGNED)
		VulnerabilitySeverity nvdSeverity = null;
		for (SeveritySourceDto severityDto : severities) {
			if (severityDto.source() == SeveritySource.NVD) {
				nvdSeverity = severityDto.severity();
				if (nvdSeverity != VulnerabilitySeverity.UNASSIGNED) {
					return nvdSeverity;
				}
				break;
			}
		}
		
		// If NVD is UNASSIGNED or not found, look for GHSA severity
		for (SeveritySourceDto severityDto : severities) {
			if (severityDto.source() == SeveritySource.GHSA) {
				return severityDto.severity();
			}
		}
		
		// If GHSA not found and NVD was UNASSIGNED, return NVD UNASSIGNED
		if (nvdSeverity != null) {
			return nvdSeverity;
		}
		
		// If no ANALYSIS, NVD or GHSA found, use OTHER or any available
		for (SeveritySourceDto severityDto : severities) {
			if (severityDto.source() == SeveritySource.OTHER) {
				return severityDto.severity();
			}
		}
		
		// Fallback to first available severity
		return severities.iterator().next().severity();
	}
	
	/**
	 * Selects the earlier of two dates. If both are present, returns the earlier one.
	 * If only one is present, returns that one. If both are null, returns null.
	 */
	private ZonedDateTime selectEarlierDate(ZonedDateTime date1, ZonedDateTime date2) {
		if (date1 == null) {
			return date2;
		}
		if (date2 == null) {
			return date1;
		}
		return date1.isBefore(date2) ? date1 : date2;
	}
  
	public void mergeWithByMetrics(ReleaseMetricsDto otherRmd) {
	    if (otherRmd != null) {
	    	this.critical += otherRmd.critical != null ? otherRmd.critical : 0;
		    this.high += otherRmd.high != null ? otherRmd.high : 0;
		    this.medium += otherRmd.medium != null ? otherRmd.medium : 0;
		    this.low += otherRmd.low != null ? otherRmd.low : 0;
		    this.unassigned += otherRmd.unassigned != null ? otherRmd.unassigned : 0;
		    this.vulnerabilities += otherRmd.vulnerabilities != null ? otherRmd.vulnerabilities : 0;
		    this.vulnerableComponents += otherRmd.vulnerableComponents != null ? otherRmd.vulnerableComponents : 0;
		    this.components += otherRmd.components != null ? otherRmd.components : 0;
		    this.suppressed += otherRmd.suppressed != null ? otherRmd.suppressed : 0;
		    this.findingsTotal += otherRmd.findingsTotal != null ? otherRmd.findingsTotal : 0;
		    this.findingsAudited += otherRmd.findingsAudited != null ? otherRmd.findingsAudited : 0;
		    this.findingsUnaudited += otherRmd.findingsUnaudited != null ? otherRmd.findingsUnaudited : 0;
		    this.inheritedRiskScore += otherRmd.inheritedRiskScore != null ? otherRmd.inheritedRiskScore : 0;
		    this.policyViolationsFail += otherRmd.policyViolationsFail != null ? otherRmd.policyViolationsFail : 0;
		    this.policyViolationsWarn += otherRmd.policyViolationsWarn != null ? otherRmd.policyViolationsWarn : 0;
		    this.policyViolationsInfo += otherRmd.policyViolationsInfo != null ? otherRmd.policyViolationsInfo : 0;
		    this.policyViolationsTotal += otherRmd.policyViolationsTotal != null ? otherRmd.policyViolationsTotal : 0;
		    this.policyViolationsAudited += otherRmd.policyViolationsAudited != null ? otherRmd.policyViolationsAudited : 0;
		    this.policyViolationsUnaudited += otherRmd.policyViolationsUnaudited != null ? otherRmd.policyViolationsUnaudited : 0;
		    this.policyViolationsSecurityTotal += otherRmd.policyViolationsSecurityTotal != null ? otherRmd.policyViolationsSecurityTotal : 0;
		    this.policyViolationsSecurityAudited += otherRmd.policyViolationsSecurityAudited != null ? otherRmd.policyViolationsSecurityAudited : 0;
		    this.policyViolationsSecurityUnaudited += otherRmd.policyViolationsSecurityUnaudited != null ? otherRmd.policyViolationsSecurityUnaudited : 0;
		    this.policyViolationsLicenseTotal += otherRmd.policyViolationsLicenseTotal != null ? otherRmd.policyViolationsLicenseTotal : 0;
		    this.policyViolationsLicenseAudited += otherRmd.policyViolationsLicenseAudited != null ? otherRmd.policyViolationsLicenseAudited : 0;
		    this.policyViolationsLicenseUnaudited += otherRmd.policyViolationsLicenseUnaudited != null ? otherRmd.policyViolationsLicenseUnaudited : 0;
		    this.policyViolationsOperationalTotal += otherRmd.policyViolationsOperationalTotal != null ? otherRmd.policyViolationsOperationalTotal : 0;
		    this.policyViolationsOperationalAudited += otherRmd.policyViolationsOperationalAudited != null ? otherRmd.policyViolationsOperationalAudited : 0;
		    this.policyViolationsOperationalUnaudited += otherRmd.policyViolationsOperationalUnaudited != null ? otherRmd.policyViolationsOperationalUnaudited : 0;
	    }
	}
	
	/**
	 * Deduplicate violations by purl+type composite key
	 */
	public void deduplicateViolations() {
		if (violationDetails == null || violationDetails.isEmpty()) {
			return;
		}
		
		// Use LinkedHashMap to preserve insertion order while deduplicating
		Map<String, ViolationDto> violationMap = new LinkedHashMap<>();
		
		for (ViolationDto violation : violationDetails) {
			// Create composite key: purl + type (same as getViolationKey)
			String key = violation.purl() + violation.type().name();
			
			if (violationMap.containsKey(key)) {
				// Merge sources if duplicate found
				ViolationDto existing = violationMap.get(key);
				Set<FindingSourceDto> combinedSources = new LinkedHashSet<>();
				if (existing.sources() != null) {
					combinedSources.addAll(existing.sources());
				}
				if (violation.sources() != null) {
					combinedSources.addAll(violation.sources());
				}
				
				// Keep existing violation but with merged sources and earlier attributedAt
				ViolationDto merged = new ViolationDto(
					existing.purl(),
					existing.type(),
					existing.license(),
					existing.violationDetails(),
					combinedSources,
					existing.analysisState(),
					existing.analysisDate(),
					selectEarlierDate(existing.attributedAt(), violation.attributedAt())
				);
				violationMap.put(key, merged);
			} else {
				violationMap.put(key, violation);
			}
		}
		
		violationDetails = new LinkedList<>(violationMap.values());
	}
	
	/**
	 * Deduplicate weaknesses by fingerprint
	 */
	public void deduplicateWeaknesses() {
		if (weaknessDetails == null || weaknessDetails.isEmpty()) {
			return;
		}
		
		// Use LinkedHashMap to preserve insertion order while deduplicating
		Map<String, WeaknessDto> weaknessMap = new LinkedHashMap<>();
		
		for (WeaknessDto weakness : weaknessDetails) {
			// Use fingerprint as key (same as mergeWeaknessDtos)
			String key = weakness.fingerprint();
			
			if (weaknessMap.containsKey(key)) {
				// Merge sources if duplicate found
				WeaknessDto existing = weaknessMap.get(key);
				Set<FindingSourceDto> combinedSources = new LinkedHashSet<>();
				if (existing.sources() != null) {
					combinedSources.addAll(existing.sources());
				}
				if (weakness.sources() != null) {
					combinedSources.addAll(weakness.sources());
				}
				
				// Keep existing weakness but with merged sources and earlier attributedAt
				WeaknessDto merged = new WeaknessDto(
					existing.cweId(),
					existing.ruleId(),
					existing.location(),
					existing.fingerprint(),
					existing.severity(),
					combinedSources,
					existing.analysisState(),
					existing.analysisDate(),
					selectEarlierDate(existing.attributedAt(), weakness.attributedAt())
				);
				weaknessMap.put(key, merged);
			} else {
				weaknessMap.put(key, weakness);
			}
		}
		
		weaknessDetails = new LinkedList<>(weaknessMap.values());
	}

	public List<VulnViolationsChartDto> convertToChartDto (ZonedDateTime createdDate) {
		List<VulnViolationsChartDto> vulnViolDtos = new LinkedList<>();
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getCritical(), "Critical Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getHigh(), "High Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getMedium(), "Medium Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getLow(), "Low Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getUnassigned(), "Unassigned Vulnerabilities"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getPolicyViolationsLicenseTotal(), "License Violations"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getPolicyViolationsOperationalTotal(), "Operational Violations"));
		vulnViolDtos.add(new VulnViolationsChartDto(createdDate, this.getPolicyViolationsSecurityTotal(), "Security Violations"));
		return vulnViolDtos;
	}
}
