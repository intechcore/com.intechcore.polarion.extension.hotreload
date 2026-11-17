**Use this extension on development servers only.** It changes the code of a running Polarion server. Do not use it in production.

## The problem

Polarion 2606 reads its extensions only once, when the server starts. To use a new or changed extension, you must restart Polarion. On a development server, this costs time after every build.

## What this extension does

The extension installs, updates and uninstalls extension bundles in the running OSGi framework. It then gives Polarion the new web applications and contributions.

Polarion has no API for this. The extension writes into internal Polarion objects that Polarion fills at startup.

## Why it is risky

* Each update keeps the old code of the changed extensions in memory when other objects still refer to it. Memory use grows with every update. Restart Polarion from time to time.
* Requests to a changed extension fail while the update runs.
* A future Polarion release can change the internal objects. The update then fails, and the report shows the error. Restart Polarion to get a consistent state.
* Some contributions need a restart. The report lists them.
* In a Polarion cluster, the update applies only to the node that handles the request.
