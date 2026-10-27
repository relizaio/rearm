/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/** One part of a submission preview's plan (task TEA-35): its requirement, its effective selection and whether a generator exists. */
public record SubmissionPlanEntry(PackagePart part, PackagePartRequirement requirement,
		PackagePartSelection selection, boolean generatorPresent) {}
