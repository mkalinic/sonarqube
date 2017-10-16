/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.platform.db.migration.version.v67;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.TestSystem2;
import org.sonar.db.CoreDbTester;

import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.assertj.core.api.Assertions.assertThat;

public class CleanupDisabledUsersTest {

  private final static long PAST = 100_000_000_000L;
  private final static long NOW = 500_000_000_000L;
  private final static Random RANDOM = new Random();
  private final static String SELECT_USERS = "select name, scm_accounts, user_local, login, crypted_password, salt, email, external_identity, external_identity_provider, active, is_root, onboarded, created_at, updated_at from users";

  private System2 system2 = new TestSystem2().setNow(NOW);

  @Rule
  public CoreDbTester db = CoreDbTester.createForSchema(CleanupDisabledUsersTest.class, "users.sql");

  private CleanupDisabledUsers underTest = new CleanupDisabledUsers(db.database(), system2);

  private final List<User> users = Arrays.asList(
    new User("user1", null, null, null, null, null, false),
    new User("user2", randomAlphanumeric(10), null, null, null, null, false),
    new User("user3", null, randomAlphanumeric(10), null, null, null, false),
    new User("user4", null, null, randomAlphanumeric(10), null, null, false),
    new User("user5", null, null, null, randomAlphanumeric(10), null, false),
    new User("user6", null, null, null, null, randomAlphanumeric(10), false),
    new User("user7", randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), false),
    new User("user8", randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), true),
    new User("user9", randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), randomAlphanumeric(10), true)
  );

  @Test
  public void do_nothing_when_no_data() throws SQLException {
    underTest.execute();
  }

  @Test
  public void execute_must_update_database() throws SQLException {
    users.forEach(User::insert);

    underTest.execute();

    assertDatabaseContainsExpectedValues();
  }

  @Test
  public void migration_is_reentrant() throws SQLException {
    users.forEach(User::insert);

    underTest.execute();
    underTest.execute();

    assertDatabaseContainsExpectedValues();
  }

  private void assertDatabaseContainsExpectedValues() {
    assertThat(db.select(SELECT_USERS)).isEqualTo(
      users.stream().map(User::cleanedupUser).map(User::toMap).collect(Collectors.toList())
    );
  }

  private class User {
    private String login;
    private String cryptedPassword;
    private String salt;
    private String email;
    private String externalIdentity;
    private String externalIdentityProvider;
    private String name;
    private String scmAccounts;
    private boolean userLocal;
    private boolean active;
    private boolean isRoot;
    private boolean onBoarded;
    private long updatedAt;
    private long createdAt;

    private User(String login, @Nullable String cryptedPassword, @Nullable String salt, @Nullable String email,
      @Nullable String externalIdentity, @Nullable String externalIdentityProvider, boolean active) {
      this.login = login;
      this.cryptedPassword = cryptedPassword;
      this.salt = salt;
      this.email = email;
      this.externalIdentity = externalIdentity;
      this.externalIdentityProvider = externalIdentityProvider;
      this.active = active;
      this.isRoot = RANDOM.nextBoolean();
      this.onBoarded = RANDOM.nextBoolean();
      this.userLocal = RANDOM.nextBoolean();
      this.scmAccounts = randomAlphanumeric(1500);
      this.name = randomAlphanumeric(200);
      this.updatedAt = PAST;
      this.createdAt = PAST;
    }

    private void insert() {
      db.executeInsert("USERS", toMap());
    }

    private User cleanedupUser() {
      User cleanedupUser;
      if (active) {
        cleanedupUser = new User(login, cryptedPassword, salt, email, externalIdentity, externalIdentityProvider, true);
      } else {
        cleanedupUser = new User(login, null, null, null, null, null, false);
        if (cryptedPassword != null || salt != null || email != null || externalIdentityProvider != null || externalIdentity != null) {
          cleanedupUser.updatedAt = NOW;
        } else {
          // No change so updatedAt will not be changed
          cleanedupUser.updatedAt = PAST;
        }
      }

      cleanedupUser.name = this.name;
      cleanedupUser.scmAccounts = this.scmAccounts;
      cleanedupUser.userLocal = this.userLocal;
      cleanedupUser.onBoarded = this.onBoarded;
      cleanedupUser.isRoot = this.isRoot;
      return cleanedupUser;
    }

    private Map<String, Object> toMap() {
      HashMap<String, Object> map = new HashMap<>();
      map.put("LOGIN", login);
      map.put("IS_ROOT", isRoot);
      map.put("ONBOARDED", onBoarded);
      map.put("ACTIVE", active);
      map.put("CREATED_AT", createdAt);
      map.put("UPDATED_AT", updatedAt);
      map.put("CRYPTED_PASSWORD", cryptedPassword);
      map.put("SALT", salt);
      map.put("EMAIL", email);
      map.put("EXTERNAL_IDENTITY", externalIdentity);
      map.put("EXTERNAL_IDENTITY_PROVIDER", externalIdentityProvider);
      map.put("NAME", name);
      map.put("SCM_ACCOUNTS", scmAccounts);
      map.put("USER_LOCAL", userLocal);

      return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof User)) {
        return false;
      }
      User user = (User) o;
      return active == user.active &&
        isRoot == user.isRoot &&
        onBoarded == user.onBoarded &&
        Objects.equals(login, user.login) &&
        Objects.equals(cryptedPassword, user.cryptedPassword) &&
        Objects.equals(salt, user.salt) &&
        Objects.equals(email, user.email) &&
        Objects.equals(externalIdentity, user.externalIdentity) &&
        Objects.equals(externalIdentityProvider, user.externalIdentityProvider);
    }

    @Override
    public int hashCode() {
      return Objects.hash(login, cryptedPassword, salt, email, externalIdentity, externalIdentityProvider, active, isRoot, onBoarded);
    }
  }
}
