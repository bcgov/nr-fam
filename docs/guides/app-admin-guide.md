# How to use Forest Access Management (FAM)

<p class="subtitle">A guide for application administrators</p>

Forest Access Management (FAM) is how access to Ministry of Forests applications
is granted and removed. As an application administrator you decide who holds
which roles in your application, and who else may hand those roles out.

For support using FAM, email [Heartwood@gov.bc.ca](mailto:Heartwood@gov.bc.ca).

## Accessing FAM

You need permission before you can use FAM. If you do not have it, email
[Heartwood@gov.bc.ca](mailto:Heartwood@gov.bc.ca).

Once you have access, sign in at
[forestaccess.nrs.gov.bc.ca](https://forestaccess.nrs.gov.bc.ca) with your IDIR or Business BCeID.

![The FAM sign-in screen](screenshots/01-sign-in.png)

## Choosing an application

Select the application you want to manage from the drop-down at the top of
Manage permissions. Only applications you administer appear in the list.

Each entry shows the application's name and a pill naming the environment:
Development, Test or Production. A CSS integration spans all three and they are
administered separately — granting somebody access in Test does not give them
access in Production.

![The application picker, showing the environment pills](screenshots/03-application-picker.png)

You cannot change your own permissions. Ask another administrator if you need a
role yourself.

## The tabs

With an application chosen, the screen offers a tab per kind of access:

| Tab | Who is listed |
| --- | --- |
| Users | Everybody holding a role in the application |
| Delegated admins | People who may grant a subset of those roles to others |
| Application admins | People who administer the application, as you do |
| DevOps admins | People who may define what roles the application has |

![The tabs on Manage permissions](screenshots/12-tabs.png)

## Adding a user's permissions

1. Choose the **Users** tab.
2. Select **Add permission** at the right of the screen.

![The Users tab with the Add permission button](screenshots/04-users-tab.png)

### Finding the user

1. Choose **IDIR** or **Business BCeID**.
2. Type the person's username in the field and select **Search**.
3. Choose the right person from the results and select **Confirm**.

![Searching for a user](screenshots/05-user-search.png)

A surname search can return hundreds of people. Use the filter above the results
to narrow them, and sort by any column heading. Check the username and email
before confirming — they are how you tell two people with the same name apart.

![The user search results, filtered](screenshots/06-user-search-results.png)

You can select more than one person. Everybody chosen gets the same roles, which
is the quick way to set up a team.

### Choosing the roles

1. Select the roles you want to grant.
2. If a role needs a district, region or organization, choose those as well. Use
   commas to enter several client numbers at once.
3. Set an expiry date if the access should end on a particular day. Leave it
   empty for access that does not expire.
4. Uncheck **Send email to notify user** if you do not want FAM to email them.
5. Select **Grant permission**.

![Choosing roles and scopes](screenshots/07-choose-roles.png)

FAM returns you to Manage permissions and confirms what it granted.

![The confirmation banner after a grant](screenshots/08-grant-confirmation.png)

## Reviewing and removing a user's permissions

A permission you have just granted appears at the top of the table with a green
**New** pill.

- To see everything that has happened to somebody's access, select the clock
  icon under **Action**.
- To change what they hold, select the edit icon.
- To take the access away, select the trash can icon and confirm.

![The permissions table with a new grant at the top](screenshots/09-permissions-table.png)

## Adding a delegated administrator

A delegated administrator may grant and revoke the roles you delegate to them,
and nothing else. They cannot appoint other administrators.

1. Choose the **Delegated admins** tab.
2. Select **Add delegated admin**.
3. Find the person as above.
4. Choose the roles they may hand out. For a scoped role, choose the scope
   values too — delegating Submitter for one district does not let them grant it
   for another.
5. Select **Grant delegated admin**.

![Appointing a delegated administrator](screenshots/13-add-delegated-admin.png)

### Before appointing a Business BCeID delegated administrator

1. Make sure the Business Profile Manager exists in the BCeID white pages. The
   profile manager is the highest authority in that organization.
2. Appoint a Business BCeID delegated administrator only when the profile
   manager asks for it — either for themselves or for somebody else in their
   organization.
3. Confirm the candidate has been trained on FAM and understands what the role
   carries. They will be asked to accept the terms of use the first time they
   sign in.

## Adding an application administrator

An application administrator has the same authority you do, including appointing
other administrators. Appoint one from the **Application admins** tab.

Application administrators must be IDIR users.

![Appointing an application administrator](screenshots/14-add-application-admin.png)

## Granting to many people at once

To set up a lot of people in one go, use **Bulk grant** from Manage permissions.
Upload a CSV, review what FAM proposes, then apply it. Rows for access somebody
already holds are skipped rather than granted again.

![The bulk grant screen](screenshots/15-bulk-grant.png)

## Viewing your own permissions

To see which applications you administer and what roles you hold, select
**My permissions** in the left-hand navigation.

![The My permissions screen](screenshots/11-my-permissions.png)

## Getting help

For support using FAM, email [Heartwood@gov.bc.ca](mailto:Heartwood@gov.bc.ca),
or use **Report an issue** in the left-hand navigation.
