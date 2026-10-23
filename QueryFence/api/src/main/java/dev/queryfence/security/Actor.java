package dev.queryfence.security;

import java.util.Set;

public record Actor(
    String id, RolePolicy.Role role, Set<String> scopes, Set<String> origins, boolean demo) {
  public void require(String scope) {
    if (!scopes.contains(scope))
      throw new ApiProblem(403, "INSUFFICIENT_SCOPE", "Credential does not allow " + scope + ".");
  }
}
