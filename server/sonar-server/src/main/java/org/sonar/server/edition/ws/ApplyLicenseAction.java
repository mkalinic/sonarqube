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
package org.sonar.server.edition.ws;

import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.server.edition.EditionManagementState;
import org.sonar.server.edition.License;
import org.sonar.server.edition.MutableEditionManagementState;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.plugins.edition.EditionInstaller;
import org.sonar.server.user.UserSession;
import org.sonar.server.ws.WsUtils;
import org.sonarqube.ws.WsEditions;

public class ApplyLicenseAction implements EditionsWsAction {
  private static final String PARAM_LICENSE = "license";

  private final UserSession userSession;
  private final MutableEditionManagementState editionManagementState;
  private final EditionInstaller editionInstaller;

  public ApplyLicenseAction(UserSession userSession, MutableEditionManagementState editionManagementState, EditionInstaller editionInstaller) {
    this.userSession = userSession;
    this.editionManagementState = editionManagementState;
    this.editionInstaller = editionInstaller;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction("apply_license")
      .setSince("6.7")
      .setPost(true)
      .setDescription("Apply changes to SonarQube to match the specified license. Require 'Administer System' permission.")
      .setResponseExample(getClass().getResource("example-edition-apply_license.json"))
      .setHandler(this);

    action.createParam(PARAM_LICENSE)
      .setRequired(true)
      .setSince("6.7")
      .setDescription("the license");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    userSession.checkLoggedIn().checkIsSystemAdministrator();

    if (editionManagementState.getPendingInstallationStatus() != EditionManagementState.PendingStatus.NONE) {
      throw BadRequestException.create("Can't apply a license when applying one is already in progress");
    }

    String licenseParam = request.mandatoryParam(PARAM_LICENSE);
    License newLicense = License.parse(licenseParam).orElseThrow(() -> BadRequestException.create("The license provided is invalid"));

    if (!editionInstaller.requiresInstallationChange(newLicense.getPluginKeys())) {
      editionManagementState.newEditionWithoutInstall(newLicense.getEditionKey());
      // TODO install with license plugin
    } else {
      boolean online = editionInstaller.install(newLicense.getPluginKeys());
      if (online) {
        editionManagementState.startAutomaticInstall(newLicense);
      } else {
        editionManagementState.startManualInstall(newLicense);
      }
    }

    WsUtils.writeProtobuf(buildResponse(), request, response);
  }

  private WsEditions.StatusResponse buildResponse() {
    return WsEditions.StatusResponse.newBuilder()
      .setNextEditionKey(editionManagementState.getPendingEditionKey().orElse(""))
      .setCurrentEditionKey(editionManagementState.getCurrentEditionKey().orElse(""))
      .setInstallationStatus(WsEditions.InstallationStatus.valueOf(editionManagementState.getPendingInstallationStatus().name()))
      .build();
  }
}
