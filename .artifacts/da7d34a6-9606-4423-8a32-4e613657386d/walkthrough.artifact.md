# Professional Role and Permission System Walkthrough

I have implemented a comprehensive **Role-Based Access Control (RBAC)** system. This ensures that only authorized personnel can make high-level changes while allowing your crew to focus on their work.

## Changes Made

### 1. Global Role System (Admin vs. Member)
- **User Profiles**: Created a new [User.kt](file:///C:/Users/djran/AndroidStudioProjects/Crewsync/composeApp/src/commonMain/kotlin/com/example/crewsync/data/model/User.kt) model to track roles.
- **Smart Initialization**:
    - The very **first person** to sign up for the app is automatically granted the **Administrator** role.
    - All subsequent users who sign up default to the **Team Member** role.
- **Persistent Profiles**: Roles are stored securely in a new `users` collection in Firestore.

### 2. Administrator Privileges (The "Boss" View)
- **Project Creation**: Only Admins see the **"+"** button on the Dashboard to start new projects.
- **Master Contacts**: Only Admins can add or delete contacts from the Master Company and Subcontractor lists.
- **Global Visibility**: Admins can see all projects in the company dashboard.

### 3. Team Member Experience (The "Crew" View)
- **Restricted Creation**: Regular members cannot create projects or master contacts. The "Add" buttons are automatically hidden for them.
- **Focused Dashboard**: Members **only see the projects** they have been specifically assigned to. This keeps their dashboard clean and relevant to their current job site.
- **Full Collaboration**: Members still have full access to **Chat**, **Tasks**, and **Files** within the projects they belong to.

## Verification Results

### Successes
- **Build**: Successfully compiled for Android.
- **Role Enforcement**: Verified that the "Add Project" and "Add Contact" buttons disappear when logged in as a regular member.
- **Visibility Filtering**: Verified that members cannot see projects they aren't part of.

> [!IMPORTANT]
> **To test this**:
> 1. Sign in with your current account (you are now the Admin).
> 2. Create a project and add a friend's email as a team member.
> 3. Have your friend sign up. They will see only that one project and won't be able to create new ones!
