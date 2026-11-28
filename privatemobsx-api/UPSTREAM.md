# PrivateMobsX API compile contract

This module contains an exact compile-time copy of the public API module from:

- Repository: https://github.com/makarovlev/PrivateMobsX
- Branch: feature/privatemobsx-api
- Source commit: 7a1c33eb41a821f7749916ed02762f5924a2173b
- Maven coordinates: com.gachicraft:privatemobsx-api:1.2.0

The IronFrontiers plugin dependency is declared with Maven scope `provided`.
The API classes are therefore compiled against but are not shaded into
IronFrontiers.jar. The required PrivateMobsX plugin provides the actual API
classes and registers the service through Bukkit's ServicesManager.

When the public API contract changes, update the source files in this module
and this pin together. Do not change the dependency to compile/runtime or shade
these classes into IronFrontiers: duplicate copies would break the ServicesManager
class identity between the provider and consumer plugins.
