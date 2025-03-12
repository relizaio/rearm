# Bundling Components into Products

## Description
Bundling is a process of *integrating* several Components into a single Product. A Product release stores versions of all individual Components and can be used as a single manifest in the upgrade process.

## Create Product
To create a Product, navigate to the `Product` menu of ReARM and click on the `plus-circle icon`.

Choose desired name and version schema. 

Once created, ReARM would automatically provision *Base Feature Set* for this product.

## Set up Auto-Integrate
Once product is provision, you may set up auto-integration logic of Component releases into Product releases. For this, select desired *Feature Set* and click on the `wrench icon`.

In the opened modal, click on the `plus-circle icon` near *Dependency Requirements* and pick desired components and their branches for auto-integrate. Optionally, it is possible to pin specific release for the auto-integrate.

*Component Requirement Status* can be one of 3 things:
- *Required* - Component is a part of auto-integrate and will also be used for strict matching. This means that ReARM will only recognize this Product if the Component is present.
- *Transient* - Component is a part of auto-integrate but will only be used for matching to Product if it is present at the instance. So if the component is not present on the instance, but all other components are present, ReARM will consider this Product as matching.
- *Ignored* - Component will not be part of auto-integrate, nor of matching. ReARM will completely ignore it if encounters.

Once all integrate components are chosen, switch *Auto Integrate* selector to *ENABLED*. This will ensure that any new incoming component release will trigger creation of a new version of this *Product*.
