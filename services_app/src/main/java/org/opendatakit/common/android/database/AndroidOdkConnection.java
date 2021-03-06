/*
 * Copyright (C) 2015 University of Washington
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

package org.opendatakit.common.android.database;

import android.content.ContentValues;
import android.database.Cursor;

import org.opendatakit.common.android.utilities.ODKFileUtils;
import org.opendatakit.common.android.utilities.WebLogger;
import org.sqlite.database.SQLException;
import org.sqlite.database.sqlite.SQLiteConnection;
import org.sqlite.database.sqlite.SQLiteDatabaseConfiguration;
import org.sqlite.database.sqlite.SQLiteException;

import java.io.File;

public class AndroidOdkConnection implements OdkConnectionInterface{
  final Object mutex;
  /**
   * Reference count is pre-incremented to account for:
   *
   * One reference immediately held on stack after creation.
   * One reference will be added when we put this into the OdkConnectionFactoryInterface session map
   */
  final OperationLog operationLog;
  final String appName;
  final SQLiteConnection db;
  final String sessionQualifier;
  int referenceCount = 1;
  Object initializationMutex = new Object();
  boolean initializationComplete = false;
  boolean initializationStatus = false;


   private static String getDbFilePath(String appName) {
      File dbFile = new File(ODKFileUtils.getWebDbFolder(appName),
          ODKFileUtils.getNameOfSQLiteDatabase());
      String dbFilePath = dbFile.getAbsolutePath();
      return dbFilePath;
   }

  public static AndroidOdkConnection openDatabase(AppNameSharedStateContainer
      appNameSharedStateContainer, String sessionQualifier) {

     String appName = appNameSharedStateContainer.getAppName();
     String dbFilePath = getDbFilePath(appName);

     SQLiteDatabaseConfiguration configuration = new SQLiteDatabaseConfiguration(appName, dbFilePath,
         SQLiteConnection.ENABLE_WRITE_AHEAD_LOGGING |
             SQLiteConnection.OPEN_READWRITE | SQLiteConnection.CREATE_IF_NECESSARY |
             SQLiteConnection.NO_LOCALIZED_COLLATORS, sessionQualifier );

     boolean success = false;
     SQLiteConnection db = null;
     try {
        db = new SQLiteConnection(configuration, appNameSharedStateContainer.getOperationLog(),
            null, sessionQualifier);

        // this might throw an exception
        db.open();

        // this isn't going to throw an exception
        AndroidOdkConnection connection =
           new AndroidOdkConnection(appNameSharedStateContainer.getSessionMutex(), appName,
                   appNameSharedStateContainer.getOperationLog(), db, sessionQualifier);
        success = true;
        return connection;
     } finally {
        if ( !success ) {
           db.releaseReference();
        }
     }
  }

  private AndroidOdkConnection(Object mutex, String appName, OperationLog operationLog,
      SQLiteConnection db, String
      sessionQualifier) {
    this.mutex = mutex;
    this.appName = appName;
    this.operationLog = operationLog;
    this.db = db;
    this.sessionQualifier = sessionQualifier;
  }

  public boolean waitForInitializationComplete() {
    for(;;) {
      try {
        synchronized (initializationMutex) {
          if ( initializationComplete ) {
            return initializationStatus;
          }
          initializationMutex.wait(100L);
          if ( initializationComplete ) {
            return initializationStatus;
          }
        }
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      WebLogger.getLogger(appName).i("AndroidOdkConnection", "waitForInitializationComplete - spin waiting " + Thread.currentThread().getId());
    }
  }

  public void signalInitializationComplete(boolean outcome) {
    synchronized (initializationMutex) {
      initializationStatus = outcome;
      initializationComplete = true;
      initializationMutex.notifyAll();
    }
  }

  public String getAppName() {
    return appName;
  }

  public String getSessionQualifier() {
    return sessionQualifier;
  }

  public void dumpDetail(StringBuilder b) {
    db.dump(b, true);
  }

  private String getLogTag() {
    return "AndroidOdkConnection:" + appName + ":" + sessionQualifier;
  }

  public void acquireReference() {
    synchronized (mutex) {
      ++referenceCount;
    }
  }

  public void releaseReference() {
    boolean refCountIsZero = false;
    boolean refCountIsNegative = false;
    synchronized (mutex) {
      --referenceCount;
      refCountIsZero = (referenceCount == 0);
      refCountIsNegative = (referenceCount < 0);
    }
    try {
      if (refCountIsZero) {
        commonWrapUpConnection("releaseReferenceIsZero");
      } else if (refCountIsNegative) {
        commonWrapUpConnection("releaseReferenceIsNegative");
      }
    } catch ( Throwable t) {
      WebLogger.getLogger(appName).e(getLogTag(), "ReleaseReference tried to throw an exception!");
      WebLogger.getLogger(appName).printStackTrace(t);
    }
  }

  public int getReferenceCount() {
     // don't log this -- used by dump()
     synchronized (mutex) {
        return referenceCount;
     }
  }

  public boolean isOpen() {
     final int cookie = operationLog.beginOperation(sessionQualifier, "isOpen()",null, null);;
     try {
        synchronized (mutex) {
           return db.isOpen();
        }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  protected void finalize() throws Throwable {

    try {
      int refCount = getReferenceCount();
      if (refCount != 0) {
        WebLogger.getLogger(appName).w(getLogTag(), "finalize: expected no references -- has " + refCount);
      }
      commonWrapUpConnection("finalize");
    } finally {
      super.finalize();
    }
  }

  public void close() {
    throw new IllegalStateException("this method should not be called");
  }

  private void commonWrapUpConnection(String action) throws Throwable {
     final int cookie = operationLog.beginOperation(sessionQualifier,
         "commonWrapUpConnection(\"" + action + "\")",null, null);;
     try {
       if ( isOpen() ) {
         try {
           while (inTransaction()) {
             endTransaction();
           }
         } finally {
            final int innerCookie = operationLog.beginOperation(sessionQualifier,
                "commonWrapUpConnection(\"" + action + "\") -- close",null, null);;
            try {
              synchronized (mutex) {
                db.close();
              }
            } catch ( Throwable t ) {
               operationLog.failOperation(innerCookie, t);
               if ( t instanceof SQLiteException ) {
                  throw t;
               } else {
                  throw new SQLiteException("unexpected", t);
               }
            } finally {
               operationLog.endOperation(innerCookie);
            }
         }
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public int getVersion() throws SQLiteException {
    final int cookie = operationLog.beginOperation(sessionQualifier, "getVersion()",null, null);;
    try {
       synchronized (mutex) {
          return db.getVersion();
       }
    } catch ( Throwable t ) {
       operationLog.failOperation(cookie, t);
       if ( t instanceof SQLiteException ) {
          throw t;
       } else {
          throw new SQLiteException("unexpected", t);
       }
    } finally {
       operationLog.endOperation(cookie);
    }
  }

  public void setVersion(int version) throws SQLiteException {
    final int cookie = operationLog.beginOperation(sessionQualifier,
        "setVersion(" + version + ")", null, null);;
    try {
       synchronized (mutex) {
         db.setVersion(version);
       }
    } catch ( Throwable t ) {
       operationLog.failOperation(cookie, t);
       if ( t instanceof SQLiteException ) {
          throw t;
       } else {
          throw new SQLiteException("unexpected", t);
       }
    } finally {
       operationLog.endOperation(cookie);
    }
  }

   public void beginTransactionExclusive() throws SQLException {
      boolean success = false;
      final int cookie = operationLog.beginOperation(sessionQualifier,
          "beginTransactionExclusive()", null, null);;
      try {
         synchronized (mutex) {
            db.beginTransaction(SQLiteConnection.TRANSACTION_MODE_IMMEDIATE, null);
         }
         success = true;
      } catch ( Throwable t ) {
         operationLog.failOperation(cookie, t);
         if ( t instanceof SQLiteException ) {
            throw t;
         } else {
            throw new SQLiteException("unexpected", t);
         }
      } finally {
         operationLog.endOperation(cookie);
      }
      if ( !success ) {
         WebLogger.getLogger(appName).e("AndroidOdkConnection", "Attempting dump of all database connections");
         OdkConnectionFactorySingleton.getOdkConnectionFactoryInterface().dumpInfo(true);
      }
   }

  public void beginTransactionNonExclusive() throws SQLException {
     boolean success = false;
     final int cookie = operationLog.beginOperation(sessionQualifier,
         "beginTransactionNonExclusive()", null, null);;
     try {
        synchronized (mutex) {
           db.beginTransactionNonExclusive();
        }
        success = true;
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
     if ( !success ) {
      WebLogger.getLogger(appName).e("AndroidOdkConnection", "Attempting dump of all database connections");
      OdkConnectionFactorySingleton.getOdkConnectionFactoryInterface().dumpInfo(true);
    }
  }

  public boolean inTransaction() {
      final int cookie = operationLog.beginOperation(sessionQualifier,
          "inTransaction()", null, null);;
      try {
         synchronized (mutex) {
            return db.inTransaction();
         }
      } catch ( Throwable t ) {
         operationLog.failOperation(cookie, t);
         if ( t instanceof SQLiteException ) {
            throw t;
         } else {
            throw new SQLiteException("unexpected", t);
         }
      } finally {
         operationLog.endOperation(cookie);
      }
  }

  public void setTransactionSuccessful() {
     final int cookie = operationLog.beginOperation(sessionQualifier,
         "setTransactionSuccessful()", null, null);;
     try {
       synchronized (mutex) {
         db.setTransactionSuccessful();
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public void endTransaction() {
     final int cookie = operationLog.beginOperation(sessionQualifier,
         "endTransaction()", null, null);;
     try {
       synchronized (mutex) {
         db.endTransaction();
       }
     } catch ( Throwable t ) {
         operationLog.failOperation(cookie, t);
         if ( t instanceof SQLiteException ) {
            throw t;
         } else {
            throw new SQLiteException("unexpected", t);
         }
      } finally {
         operationLog.endOperation(cookie);
      }
  }

  public int update(String table, ContentValues values, String whereClause, String[] whereArgs) {
     StringBuilder b = new StringBuilder();
     b.append("delete(\"").append(table).append("\",...,");
     if ( whereClause == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(whereClause).append("\",");
     }
     if ( whereArgs == null ) {
        b.append("null)");
     } else {
        b.append("...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.update(table, values, whereClause, whereArgs);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public int delete(String table, String whereClause, String[] whereArgs) {
     StringBuilder b = new StringBuilder();
     b.append("delete(\"").append(table).append("\",");
     if ( whereClause == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(whereClause).append("\",");
     }
     if ( whereArgs == null ) {
        b.append("null)");
     } else {
        b.append("...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.delete(table, whereClause, whereArgs);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public long replaceOrThrow(String table, String nullColumnHack, ContentValues initialValues)
      throws SQLException {
     StringBuilder b = new StringBuilder();
     b.append("replaceOrThrow(\"").append(table).append("\",");
     if ( nullColumnHack == null ) {
        b.append("null,...)");
     } else {
        b.append("\"").append(nullColumnHack).append("\",...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.replaceOrThrow(table, nullColumnHack, initialValues);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public long insertOrThrow(String table, String nullColumnHack, ContentValues values)
      throws SQLException {
     StringBuilder b = new StringBuilder();
     b.append("insertOrThrow(\"").append(table).append("\",");
     if ( nullColumnHack == null ) {
        b.append("null,...)");
     } else {
        b.append("\"").append(nullColumnHack).append("\",...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.insertOrThrow(table, nullColumnHack, values);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public void execSQL(String sql, Object[] bindArgs) throws SQLException {
     StringBuilder b = new StringBuilder();
     b.append("execSQL(\"").append(sql).append("\",");
     if ( bindArgs == null ) {
        b.append("null)");
     } else {
        b.append("...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         db.execSQL(sql, bindArgs);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public Cursor rawQuery(String sql, String[] selectionArgs) {
     StringBuilder b = new StringBuilder();
     b.append("rawQuery(\"").append(sql).append("\",");
     if ( selectionArgs == null ) {
        b.append("null)");
     } else {
        b.append("...)");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.rawQuery(sql, selectionArgs);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public Cursor query(String table, String[] columns, String selection, String[] selectionArgs,
      String groupBy, String having, String orderBy, String limit) {
     StringBuilder b = new StringBuilder();
     b.append("query(\"").append(table).append("\",");
     if ( columns == null ) {
        b.append("null,");
     } else {
        b.append("...,");
     }
     if ( selection == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(selection).append("\",");
     }
     if ( selectionArgs == null ) {
        b.append("null,");
     } else {
        b.append("...,");
     }
     if ( groupBy == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(groupBy).append("\",");
     }
     if ( having == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(having).append("\",");
     }
     if ( orderBy == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(orderBy).append("\",");
     }
     if ( limit == null ) {
        b.append("null)");
     } else {
        b.append("\"").append(limit).append("\")");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
         b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.query(table, columns, selection, selectionArgs, groupBy, having, orderBy, limit);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

  public Cursor queryDistinct(String table, String[] columns, String selection,
      String[] selectionArgs, String groupBy, String having, String orderBy, String limit) {
     StringBuilder b = new StringBuilder();
     b.append("queryDistinct(\"").append(table).append("\",");
     if ( columns == null ) {
        b.append("null,");
     } else {
        b.append("...,");
     }
     if ( selection == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(selection).append("\",");
     }
     if ( selectionArgs == null ) {
        b.append("null,");
     } else {
        b.append("...,");
     }
     if ( groupBy == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(groupBy).append("\",");
     }
     if ( having == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(having).append("\",");
     }
     if ( orderBy == null ) {
        b.append("null,");
     } else {
        b.append("\"").append(orderBy).append("\",");
     }
     if ( limit == null ) {
        b.append("null)");
     } else {
        b.append("\"").append(limit).append("\")");
     }
     final int cookie = operationLog.beginOperation(sessionQualifier,
          b.toString(), null, null);;
     try {
       synchronized (mutex) {
         return db.query(true, table, columns, selection, selectionArgs, groupBy, having, orderBy,
                 limit);
       }
     } catch ( Throwable t ) {
        operationLog.failOperation(cookie, t);
        if ( t instanceof SQLiteException ) {
           throw t;
        } else {
           throw new SQLiteException("unexpected", t);
        }
     } finally {
        operationLog.endOperation(cookie);
     }
  }

}
