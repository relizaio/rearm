# Trigger GitLab CI/CD from ReARM

N.B. This functinality is not part of ReARM Community Edition and is only available on ReARM Pro.

GitLab trigger integrations is done via triggering GitLab CI/CD schedules.

## GitLab Part
1. You need to register a personal access token to trigger GitLab CI/CD schedules. In GitLab, click on your profile picture image, then click `Edit profile`, and from there choose `Access tokens` in the left menu bar.

2. On the *Personal access tokens* page, click on the `Add new token`. Enter desired token name and expiration date. Select *api* permission scope. Then click on `Create personal access token`. Note the created token value.

3. In your GitLab repository, in your desired branch, create desired *.gitlab-ci.yml* with your pipeline, the most basic hello-world pipeline may look as following:

```
stages:
  - build

build-job:
  stage: build
  script:
    - echo "Hello World..."
```

4. In your GitLab project, in the left menu bar, select `Build` -> `Pipeline schedules`. Click on `New schedule`. Enter desired description, choose any time zone and select `custom` schedule and opt for a nearly impossible schedule so that the pipeline almost never runs on its own, such as `1 1 29 2 1`. Note, that if you want to have schedules runs as well, select desired time zone and schedule CRON.

5. Select pipeline branch and set any variable inputs if desired. Then click on `Create pipeline schedule`.

6. Once your pipeline schedule is created, click on the pencil icon in its row (`Edit scheduled pipeline`) and note schedule id shown in the breadcrumbs on top of the screen after the # sign.

## ReARM Part

Note that for integration triggers firing on approval policy events, you would need an Approval Policy configured; for firing on vulnerabilities or policy violations, you would need [Dependency Track integration configured](./dtrack).

### Organization-Wide CI Integration Part (requires Organization Admin permissions)

1. In ReARM, open **Organization Settings** menu. Under **Integrations** tab, in the `CI Integrations` sub-section, click on `Add CI Integration`.

2. Enter description (try to make this descriptive as this will be used to identify integration).

3. Choose `GitLab` as CI Type. 

4. Enter your GitLab Access Token noted above in the `GitLab Authentication Token` field.

5. Click `Save`. Your CI Integration is now created.


### Component Part (requires User with Write permissions)

1. In ReARM, make sure you register your VCS repository that contains desired GitLab CI/CD script either via Component creation or via **VCS** menu item and the plus-circle icon.

2. You need to set up a ReARM component that will have corresponding triggers configured. Once your component is created, open it and click on the tool icon to toggle component settings:
![Toggle Component Settings in ReARM UI](images/component-settings-icon.png)

3. If you are setting triggers based on approvals, make sure you have Approval Policy selected under **Core Settings** tab.

4. Open **Output Triggers** tab and click on `Add Output Trigger`.

5. Enter name for your trigger, i.e. `Trigger GitLab CI/CD`.

6. Select `External Integration` as *Type*.

7. Choose your previously created GitLab Integration in the `Choose CI Integration` field.

8. Enter your GitLab Schedule ID in the `GitLab Schedule Id` field as noted above.

9. Under *CI Repository* click on the Edit icon and select your GitLab repository containing desired CI workflow set up above.

10. Click on 'Save', your trigger is now created.

11. Now create a Trigger Event linked to this trigger to make it fire on desired events (TODO - to be documented soon).