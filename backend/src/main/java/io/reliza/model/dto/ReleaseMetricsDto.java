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
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
	
	public static record ViolationDto (String purl, ViolationType type, String License, String violationDetails) {}
	
	public static record VulnerabilityDto (String purl, String vulnId, VulnerabilitySeverity severity) {}

	/**
	 * We use weaknessDto to store findngs from SARIF parsing
	 */
	public static record WeaknessDto (String cweId, String ruleId, String location, String fingerprint, VulnerabilitySeverity severity) {}
	
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
  		int operationalViolations = 0;
  		int securityViolations = 0;
  		int licenseViolations = 0;
  		int criticalVulns = 0;
  		int highVulns = 0;
  		int mediumVulns = 0;
  		int lowVulns = 0;
  		int unassignedVulns = 0;
  		
  		for (var x: violationDetails) {
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

		for (var x: weaknessDetails) {
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
	
	private String getVulnKey (VulnerabilityDto vulnDto) {
		return vulnDto.purl + vulnDto.vulnId;
	}
	
	private List<VulnerabilityDto> mergeVulnerabilityDtos(List<VulnerabilityDto> list1, List<VulnerabilityDto> list2) {
		Map<String, VulnerabilityDto> vulnMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> getVulnKey(x), Function.identity())));
		list2.forEach(x -> {
			String xKey = getVulnKey(x);
			if (!vulnMap.containsKey(xKey)) vulnMap.put(xKey, x);
		});
		return new LinkedList<>(vulnMap.values());
	}
	
	private String getViolationKey (ViolationDto violationDto) {
		return violationDto.purl + violationDto.type.name();
	}
	
	private List<ViolationDto> mergeViolationDtos(List<ViolationDto> list1, List<ViolationDto> list2) {
		Map<String, ViolationDto> violationMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> getViolationKey(x), Function.identity())));
		list2.forEach(x -> {
			String xKey = getViolationKey(x);
			if (!violationMap.containsKey(xKey)) violationMap.put(xKey, x);
		});
		return new LinkedList<>(violationMap.values());
	}

	private List<WeaknessDto> mergeWeaknessDtos(List<WeaknessDto> list1, List<WeaknessDto> list2) {
		Map<String, WeaknessDto> vulnMap = new LinkedHashMap<>(list1.stream()
				.collect(Collectors.toMap(x -> x.fingerprint(), Function.identity())));
		list2.forEach(x -> {
			String xKey = x.fingerprint();
			if (!vulnMap.containsKey(xKey)) vulnMap.put(xKey, x);
		});
		return new LinkedList<>(vulnMap.values());
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
}
