{{/*
Expand the name of the chart.
*/}}
{{- define "rearm.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "rearm.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "rearm.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "rearm.labels" -}}
helm.sh/chart: {{ include "rearm.chart" . }}
{{ include "rearm.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "rearm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rearm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Bundled registry password.

Single source of truth: the zot Secret renders it, and the OCI artifact
service checksums it to roll when it changes. In "generated" mode the
existing Secret is looked up first so an upgrade does not rotate the
password out from under artifacts already pushed under it. In "external"
mode the chart never sees the value, so this renders empty.
*/}}
{{- define "rearm.zotPassword" -}}
{{- $br := .Values.ociArtifactService.bundledRegistry -}}
{{- $mode := $br.create_secret_in_chart | default "generated" -}}
{{- if eq $mode "plaintext" -}}
{{- if not $br.password -}}
{{- fail "ociArtifactService.bundledRegistry.password is required when create_secret_in_chart is \"plaintext\"" -}}
{{- end -}}
{{- $br.password -}}
{{- else if eq $mode "generated" -}}
{{- if not (hasKey .Values "zotGeneratedPassword") -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (printf "%s-zot-credentials" .Release.Name) -}}
{{- $pw := "" -}}
{{- if and $existing $existing.data (index $existing.data "REGISTRY_TOKEN") -}}
{{- $pw = index $existing.data "REGISTRY_TOKEN" | b64dec -}}
{{- else -}}
{{- $pw = randAlphaNum 32 -}}
{{- end -}}
{{- /* Memoized: this template is rendered by both the Secret and the
       artifact service's rollout checksum. On a first install lookup
       finds nothing and randAlphaNum would hand each caller a DIFFERENT
       password, so the checksum would describe a credential the Secret
       never received and every deployment would roll once for nothing. */}}
{{- $_ := set .Values "zotGeneratedPassword" $pw -}}
{{- end -}}
{{- .Values.zotGeneratedPassword -}}
{{- end -}}
{{- end -}}

{{/*
Registry connection env for everything that talks to the artifact
registry. CE has a single consumer (the OCI artifact service); the
helper mirrors the Pro chart so the two stay diffable.

Kept in one place because these have to agree: pointing one at the
bundled registry while the other still reads the external
oci-registry-secrets (which the chart stops creating when the bundled
registry is on) leaves it authenticating with nothing.

The external-registry secret is optional, as it has always been for the
artifact service: the chart creates it by default, and a deployment that
manages it outside the chart should not be blocked from starting.
*/}}
{{- define "rearm.ociRegistryEnv" -}}
{{- $br := .Values.ociArtifactService.bundledRegistry -}}
{{- if $br.enabled }}
{{- $zotSecret := $br.existingSecret | default (printf "%s-zot-credentials" .Release.Name) }}
- name: REGISTRY_HOST
  value: {{ printf "%s-zot:5000" .Release.Name | quote }}
- name: REGISTRY_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ $zotSecret }}
      key: REGISTRY_USERNAME
- name: REGISTRY_TOKEN
  valueFrom:
    secretKeyRef:
      name: {{ $zotSecret }}
      key: REGISTRY_TOKEN
{{- else }}
- name: REGISTRY_HOST
  value: {{ .Values.ociArtifactService.registryHost }}
- name: REGISTRY_USERNAME
  valueFrom:
    secretKeyRef:
      name: oci-registry-secrets
      key: REGISTRY_USERNAME
      optional: true
- name: REGISTRY_TOKEN
  valueFrom:
    secretKeyRef:
      name: oci-registry-secrets
      key: REGISTRY_TOKEN
      optional: true
{{- end }}
{{- end -}}

{{/*
Whether registry traffic is plain HTTP. The bundled registry is
in-cluster HTTP; an external one must not be, so the configured value
only applies when the bundled registry is off.
*/}}
{{- define "rearm.ociPlainHttp" -}}
{{- ternary "true" (.Values.ociArtifactService.usePlainHttp | default "false" | toString) .Values.ociArtifactService.bundledRegistry.enabled -}}
{{- end -}}
