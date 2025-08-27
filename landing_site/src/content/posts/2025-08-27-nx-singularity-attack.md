---
title: "'Singularity' Supply Chain Attack on Nx npm Packages - GHSA-cxm3-wv7p-598c"
date: "2025-08-27"
---

Multiple malicious versions of Nx and some supporting plugins were published to npm. Official advisory can be found [here](https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c). This is the latest supply chain attack to hit npm ecosystem with large volume of daily downloads. We have described previous attack [in this blog](https://rearmhq.com/blog/2025-07-21-how-rearm-helps-mitigate-recent-npm-injections/).

This new Nx attack is also called 'singularity' as it creates GitHub repositories named 's1ngularity-repository', 's1ngularity-repository-0', or 's1ngularity-repository-1'.

The following versions are affected:
- @nx/devkit 21.5.0, 20.9.0
- @nx/enterprise-cloud 3.2.0
- @nx/eslint 21.5.0
- @nx/js 21.5.0, 20.9.0
- @nx/key 3.2.0
- @nx/node 21.5.0, 20.9.0
- @nx/workspace 21.5.0, 20.9.0
- nx 21.5.0, 20.9.0, 20.10.0, 21.6.0, 20.11.0, 21.7.0, 21.8.0, 20.12.0

Note that affected versions have already been removed from npm.

To check if you are affected, if you are using ReARM, search for "@nx" and "nx" by SBOM components to check whether any of these are present in your supply chain.

Also, check https://github.com/[GithubSlug]?tab=repositories&q=s1ngularity-repository (replace [GithubSlug] with your user or organization name) to see if a malicious repository was published to your GitHub account.

Then refer to the [advisory](https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c) for more details about required steps and mitigations.