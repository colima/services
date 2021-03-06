/*
 * Copyright (C) 2014 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.resolve.views.components;

/**
 * Represents a column that is not in conflict--i.e. one that has the same value
 * locally and on the server or in the original row and most recent checkpoint.
 *
 * @author sudar.sam@gmail.com
 *
 */
public final class ConcordantColumn {
  private final int position;
  private final String title;
  private final String displayValue;

  public ConcordantColumn(int position, String title, String displayValue) {
    this.position = position;
    this.title = title;
    this.displayValue = displayValue;
  }

  public int getPosition() {
    return this.position;
  }

  public String getTitle() {
    return this.title;
  }

  public String getDisplayValue() {
    return this.displayValue;
  }
}