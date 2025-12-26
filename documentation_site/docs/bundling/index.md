# Bundling Components into Products

## Description
Bundling is a process of *integrating* several Components into a single Product. A Product release stores versions of all individual Components and can be used as a single manifest in the upgrade process.

## Create Product
To create a Product, navigate to the `Product` menu of ReARM and click on the `plus-circle icon`.

Choose desired name and version schema. 

Once created, ReARM would automatically provision *Base Feature Set* for this product.

## Set up Auto-Integrate
Once product is provisioned, you may set up auto-integration logic of Component releases into Product releases. For this, select desired *Feature Set* and click on the `wrench icon`.

### Dependency Patterns
Dependency Patterns allow automatic matching of components based on Java regex patterns. This is useful for dynamically including components without manually adding each one.

To add a pattern, click `Add Pattern` button and enter a Java regex pattern. For example:
- `^myapp-.*` matches all components starting with "myapp-"
- `.*-service$` matches all components ending with "-service"
- `^(frontend|backend|api)$` matches exactly "frontend", "backend", or "api"

Pattern-matched dependencies are automatically included in auto-integrate when their releases arrive.

### Manual Dependencies
To add specific components, click `Add Component Dependency` or `Add Product Dependency` button. Select the desired component/product and branch. Optionally, pin a specific release for the auto-integrate.

### Dependency Management
All dependencies (pattern-matched and manual) appear in the *Dependencies* table. You can:
- **Edit** any dependency to change branch, release, status, or follow version
- **Pin releases** to specific versions for controlled integration
- **Set Follow Version** on one dependency to track its version for the Product (only one allowed)
- **Delete** manual dependencies or change their status to IGNORED

### Dependency Status
*Component Requirement Status* can be one of 3 things:
- *Required* - Component is a part of auto-integrate and will also be used for strict matching. This means that ReARM will only recognize this Product if the Component is present.
- *Transient* - Component is a part of auto-integrate but will only be used for matching to Product if it is present at the instance. So if the component is not present on the instance, but all other components are present, ReARM will consider this Product as matching.
- *Ignored* - Component will not be part of auto-integrate, nor of matching. ReARM will completely ignore it if encounters.


### Pattern and Manual Dependency Precedence
When a component matches both a dependency pattern and has a manual entry, the **manual entry takes precedence** in the effective dependencies list.

Editing a pattern-matched dependency automatically creates a manual entry for that component, which then overrides the pattern-matched version. This effectively converts the pattern-matched dependency to manual while the pattern continues to exist and may match other components.

### Enable Auto-Integrate
Once all dependencies are configured, switch *Auto Integrate* selector to *ENABLED*. This ensures that any new incoming component release matching your patterns or manual dependencies will trigger creation of a new version of this *Product*.

Changes to dependencies, patterns, and settings must be saved using the *Save Changes* button that appears when modifications are made.
