/*
 * Copyright (C) 2016 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.opendatakit.common.android.activities;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.support.annotation.StringRes;
import org.opendatakit.IntentConsts;
import org.opendatakit.common.android.logic.CommonToolProperties;
import org.opendatakit.common.android.logic.PropertiesSingleton;
import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.services.R;

import java.util.ArrayList;
import java.util.List;

/**
 * App-level settings activity.  Used across all tools.
 *
 * @author mitchellsundt@gmail.com
 */
public class AppPropertiesActivity extends PreferenceActivity implements IOdkAppPropertiesActivity {

  private static final String t = "AppPropertiesActivity";

  public static final int SPLASH_IMAGE_CHOOSER = 1;

  private static final String SAVED_ADMIN_CONFIGURED = "savedAdminConfigured";
  private String mAppName;
  private boolean mAdminMode;
  private boolean mAdminConfigured;
  
  private PropertiesSingleton mProps;

  /**
   * Populate the activity with the top-level headers.
   */
  @Override
  public void onBuildHeaders(List<Header> target) {
    List<Header> rawHeaders = new ArrayList<Header>();
    loadHeadersFromResource(R.xml.preferences_headers, rawHeaders);

    for ( Header header : rawHeaders ) {
      // if the properties are only shown in adminMode,
      // then omit them if we are not in admin mode.
      if ( header.fragmentArguments != null ) {
        boolean hasAdminModeRestriction =
          header.fragmentArguments.containsKey(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE);
        boolean hasAdminEnabledRestriction =
          header.fragmentArguments.containsKey(IntentConsts.INTENT_KEY_SETTINGS_ADMIN_ENABLED);

        boolean pass = !(hasAdminEnabledRestriction || hasAdminModeRestriction);
        if ( hasAdminEnabledRestriction ) {
          boolean enabledRequirement = header.fragmentArguments.getBoolean(IntentConsts
              .INTENT_KEY_SETTINGS_ADMIN_ENABLED);
          pass = pass || ( enabledRequirement == mAdminConfigured);
        }

        if ( hasAdminModeRestriction ) {
          boolean modeRestriction = header.fragmentArguments.getBoolean(IntentConsts
              .INTENT_KEY_SETTINGS_IN_ADMIN_MODE);
          pass = pass || ( modeRestriction == mAdminMode );
        }

        if ( !pass ) {
          continue;
        }
      }

      if ( header.id == R.id.general_settings_in_admin_mode ) {
        if ( !mAdminConfigured || mAdminMode ) {
          continue;
        }
      }

      if ( header.id == R.id.clear_configuration_settingss ) {
        // omit this if we have admin mode configured but are not in admin mode
        if ( mAdminConfigured && !mAdminMode ) {
          continue;
        }
      }

      target.add(header);
    }

    for ( Header header : target ) {
      if ( header.id == R.id.general_settings_in_admin_mode ) {
        // TODO: change to challenge for admin password and then
        // TODO: launch the general settings in admin mode.
        Intent intent = new Intent(this, AdminPasswordChallengeActivity.class);
        intent.putExtra(IntentConsts.INTENT_KEY_APP_NAME,
            this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME));
        header.intent = intent;
      }

      if ( header.id == R.id.clear_configuration_settingss ) {
        Intent intent = new Intent(this, ClearAppPropertiesActivity.class);
        intent.putExtra(IntentConsts.INTENT_KEY_APP_NAME,
            this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME));
        header.intent = intent;
      }

      if ( header.id == R.id.admin_password_status ) {
        if ( mAdminConfigured ) {
          header.titleRes = R.string.change_admin_password;
          header.summaryRes = R.string.admin_password_enabled;
        } else {
          header.titleRes = R.string.enable_admin_password;
          header.summaryRes = R.string.admin_password_disabled;
        }
      }
      if ( header.fragmentArguments != null ) {
        header.fragmentArguments.putString(IntentConsts.INTENT_KEY_APP_NAME,
            this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME));
        if ( mAdminMode ) {
          header.fragmentArguments.putBoolean(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE,
              true);
        } else {
          header.fragmentArguments.remove(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE);
        }
      }
    }
  }

  @Override
  protected boolean isValidFragment(String fragmentName) {
    return true;
  }

  public PropertiesSingleton getProps() {
    return mProps;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {

    mAppName = this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (mAppName == null || mAppName.length() == 0) {
      mAppName = ODKFileUtils.getOdkDefaultAppName();
    }

    mProps = CommonToolProperties.get(this, mAppName);

    if ( savedInstanceState != null ) {
      mAdminConfigured = savedInstanceState.getBoolean(SAVED_ADMIN_CONFIGURED);
    } else {
      String adminPwd = mProps.getProperty(CommonToolProperties.KEY_ADMIN_PW);
      mAdminConfigured = (adminPwd != null && adminPwd.length() != 0);
    }

    mAdminMode =
        this.getIntent().getBooleanExtra(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE, false);

    this.getActionBar().setTitle(
        getString((mAdminMode ?
            R.string.action_bar_general_settings_admin_mode :
            R.string.action_bar_general_settings),
            mAppName));

    super.onCreate(savedInstanceState);
  }

  @Override protected void onResume() {
    super.onResume();

    mAppName = this.getIntent().getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (mAppName == null || mAppName.length() == 0) {
      mAppName = ODKFileUtils.getOdkDefaultAppName();
    }

    mProps = CommonToolProperties.get(this, mAppName);

    mAdminMode =
        this.getIntent().getBooleanExtra(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE, false);

    String adminPwd = mProps.getProperty(CommonToolProperties.KEY_ADMIN_PW);
    boolean isAdminConfigured = (adminPwd != null && adminPwd.length() != 0);

    boolean shouldInvalidateHeaders = false;
    if ( isAdminConfigured != mAdminConfigured ) {
      mAdminConfigured = isAdminConfigured;
      shouldInvalidateHeaders = true;
    }

    if ( mAdminMode && !mAdminConfigured ) {
      // we disabled admin controls but are in the admin-level
      // settings activity.
      // back out to the non-admin-level settings activity.
      finish();
      return;
    }

    if ( shouldInvalidateHeaders ) {
      invalidateHeaders();
    }
  }

  @Override
  public Intent onBuildStartFragmentIntent(String fragmentName, Bundle args,
      @StringRes int titleRes, int shortTitleRes) {
    Intent toLaunch = super.onBuildStartFragmentIntent(fragmentName, args, titleRes, shortTitleRes);
    toLaunch.putExtra(IntentConsts.INTENT_KEY_APP_NAME, mAppName);
    if ( mAdminMode ) {
      toLaunch.putExtra(IntentConsts.INTENT_KEY_SETTINGS_IN_ADMIN_MODE, mAdminMode);
    }
    return toLaunch;
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(SAVED_ADMIN_CONFIGURED, mAdminConfigured);
    mProps.writeProperties();
  }

  @Override
  public void finish() {
    mProps.writeProperties();
    super.finish();
  }
}
