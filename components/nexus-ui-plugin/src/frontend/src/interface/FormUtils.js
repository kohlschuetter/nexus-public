/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Open Source Version is distributed with Sencha Ext JS pursuant to a FLOSS Exception agreed upon
 * between Sonatype, Inc. and Sencha Inc. Sencha Ext JS is licensed under GPL v3 and cannot be redistributed as part of a
 * closed source work.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
import {assign, Machine} from "xstate";
import ExtJS from "./ExtJS";
import UIStrings from "../constants/UIStrings";
import {hasPath, join, lensPath, path, pathOr, set, whereEq} from 'ramda';

const FIELD_ID = 'FIELD ';
const PARAMETER_ID = 'PARAMETER ';
const HELPER_BEAN = 'HelperBean.'

/**
 * @since 3.31
 */
export default class FormUtils {
  /**
   * Builds a new xstate machine used to handle forms.
   * Typically the validation action, fetchData service, and saveData service should be implemented in withConfig.
   *
   * FormUtils.buildFormMachine({id: 'MyMachine'}).withConfig({
   *   actions: {
   *     validate: assign({
   *       validationErrors: ({data}) => ({
   *         field: FormUtils.isBlank(data.field) ? UIStrings.ERROR.FIELD_REQUIRED : null
   *       })
   *     })
   *   },
   *   services: {
   *     fetchData: () => axios.get(url),
   *     saveData: ({data}) => axios.put(url, data)
   *   }
   * });
   *
   * @param id [required] a unique identifier for this machine
   * @param initial [optional] the initial state to start in, defaults to 'loading'
   * @param stateAfterSave [optional] the state to put the machine in after successful save, default of 'loaded'
   * @param config [optional] a function used to change the config of the machine
   * @return {StateMachine<any, any, AnyEventObject>}
   */
  static buildFormMachine({
                            id,
                            initial = 'loading',
                            stateAfterSave = 'loaded',
                            config = (config) => config,
                            options = (options) => options
                          })
  {
    const DEFAULT_CONFIG = {
      id,
      initial,

      context: {
        isPristine: true,
        isTouched: {},
        loadError: null,
        saveError: null,
        saveErrorData: {},
        validationErrors: {},
        data: {},
        pristineData: {}
      },

      states: {
        loading: {
          invoke: {
            id: 'fetchData',
            src: 'fetchData',
            onDone: {
              target: 'loaded',
              actions: ['setData', 'postProcessData', 'validate']
            },
            onError: {
              target: 'loadError',
              actions: ['setLoadError', 'logLoadError']
            }
          }
        },

        loaded: {
          entry: ['validate', 'setDirtyFlag', 'setIsPristine', 'onLoadedEntry'],

          on: {
            UPDATE: {
              target: 'loaded',
              actions: ['update'],
              internal: false
            },
            SAVE: {
              target: 'saving',
              cond: 'canSave'
            },
            RESET: {
              target: 'loaded',
              actions: ['reset', 'clearSaveError']
            },
            CONFIRM_DELETE: {
              target: 'confirmDelete',
              cond: 'canDelete'
            }
          }
        },

        saving: {
          entry: 'clearSaveError',

          invoke: {
            src: 'saveData',
            onDone: {
              target: stateAfterSave,
              actions: ['clearDirtyFlag', 'clearSaveError', 'setSavedData', 'logSaveSuccess', 'onSaveSuccess']
            },

            onError: {
              target: 'loaded',
              actions: ['setSaveError', 'logSaveError']
            }
          }
        },

        loadError: {
          on: {
            RETRY: {
              target: 'loading'
            }
          }
        },

        confirmDelete: {
          invoke: {
            src: 'confirmDelete',
            onDone: 'delete',
            onError: 'loaded'
          }
        },
        delete: {
          invoke: {
            src: 'delete',
            onDone: {
              target: 'loaded',
              actions: ['clearDirtyFlag', 'onDeleteSuccess']
            },
            onError: {
              target: 'loaded',
              actions: 'onDeleteError'
            }
          }
        }
      },
      on: {
        RETRY: {
          target: initial
        }
      }
    };

    const DEFAULT_OPTIONS = {
      actions: {
        setData: assign({
          data: (_, event) => event.data?.data,
          pristineData: (_, event) => event.data?.data
        }),
        postProcessData: () => {
        },
        setDirtyFlag: ({isPristine}) => ExtJS.setDirtyStatus(id, !isPristine),
        clearDirtyFlag: () => ExtJS.setDirtyStatus(id, false),

        setSaveError: assign({
          saveErrorData: ({data}) => data,
          saveError: (_, event) => {
            const data = event.data?.response?.data;
            return data instanceof String ? data : null;
          },
          saveErrors: (_, event) => {
            const data = event.data?.response?.data;
            if (data instanceof Array) {
              let saveErrors = {};
              data.forEach(({id, message}) => {
                id = id.replace(FIELD_ID, '');
                id = id.replace(PARAMETER_ID, '');
                id = id.replace(HELPER_BEAN, '');
                saveErrors[id] = message;
              });
              return saveErrors;
            }
          }
        }),
        clearSaveError: assign({
          saveErrorData: () => ({}),
          saveError: () => undefined,
          saveErrors: () => ({})
        }),
        logSaveError: (_, event) => {
          if (event.data?.message) {
            console.log(`Load Error: ${event.data?.message}`);
          }
          ExtJS.showErrorMessage(UIStrings.ERROR.SAVE_ERROR)
        },
        logSaveSuccess: () => ExtJS.showSuccessMessage(UIStrings.SAVE_SUCCESS),

        setLoadError: assign({
          loadError: (_, event) => event.data?.message
        }),
        logLoadError: (_, event) => {
          if (event.data?.message) {
            console.log(`Load Error: ${event.data?.message}`);
          }
          ExtJS.showErrorMessage(UIStrings.ERROR.LOAD_ERROR)
        },

        onSaveSuccess: () => {
        },

        reset: assign({
          data: ({pristineData}) => pristineData,
          isTouched: () => ({})
        }),

        update: assign({
          data: ({data}, event) => {
            if (event.name) {
              return set(lensPath(event.name.split('.')), event.value, data);
            }
            else if (event.data) {
              return {
                ...data,
                ...event.data
              };
            }
            else {
              console.error("update event must have a name and value or a data object", event);
            }
          },
          isTouched: ({isTouched}, event) => {
            if (event.name) {
              return set(lensPath(event.name.split('.')), true, isTouched);
            }

            const result = {...isTouched};
            Object.keys(event.data).forEach(key => result[key] = true);
            return result;
          }
        }),

        setIsPristine: assign({
          isPristine: ({data, pristineData}) => whereEq(pristineData)(data)
        }),

        setSavedData: assign({
          pristineData: ({data}) => data,
          isTouched: () => ({})
        }),

        onLoadedEntry: () => {
          /* hook for users to override on entry to the loaded state */
        }
      },

      guards: {
        canSave: ({isPristine, validationErrors}) => {
          const isValid = !FormUtils.isInvalid(validationErrors);
          return !isPristine && isValid;
        },
        canDelete: () => false
      },

      services: {
        confirmDelete: () => Promise.reject('unimplemented'),
        delete: () => Promise.reject('unimplemented'),
        fetchData: () => Promise.resolve({data: {}})
      }
    };

    return Machine(config(DEFAULT_CONFIG), options(DEFAULT_OPTIONS));
  }

  /**
   * Check if the errors object returned contains any error messages
   * @param errors {Object | null | undefined}
   * @return {boolean} true if there are any error messages
   */
  static isInvalid(errors) {
    if (errors === null || errors === undefined) {
      return false;
    }

    return Boolean(Object.values(errors).find(error => {
      if (error === null || error === undefined) {
        return false;
      }
      else if (error.length > 0) {
        return true;
      }
      else {
        return this.isInvalid(error);
      }
    }));
  }

  /**
   * Generate common props for form fields
   * @param name
   * @param current a form machine generated by buildFormMachine
   * @param defaultValue if the machine did not provide any data
   * @return {{name: *, validationErrors: (*|[]), isPristine: boolean, value: (*|string)}}
   */
  static fieldProps(name, current, defaultValue = '') {
    const {data = {}, isTouched = {}, validationErrors = {}, saveErrors = {}, saveErrorData = {}} = current.context;

    if (!Array.isArray(name)) {
      name = name.split('.');
    }

    let errors = null;
    if (path(name, isTouched) && path(name, validationErrors)) {
      errors = path(name, validationErrors);
    }
    else if (path(name, saveErrors) && path(name, saveErrorData) === path(name, data)) {
      errors = path(name, saveErrors);
    }

    return {
      id: join('.', name),
      name: join('.', name),
      value: String(pathOr(defaultValue, name, data)),
      isPristine: hasPath(name, isTouched) ? !path(name, isTouched) : true,
      validatable: true,
      validationErrors: errors || null
    };
  }

  /**
   * Generate common props for checkbox fields
   * @param name
   * @param current a form machine generated by buildFormMachine
   * @param defaultValue if the machine did not provide a value for the checkbox (defaults to false)
   * @return {{name: string, isChecked: boolean}}
   */
  static checkboxProps(name, current, defaultValue = false) {
    const {data = {}} = current.context;

    if (!Array.isArray(name)) {
      name = name.split('.');
    }

    return {
      checkboxId: String(join('.', name)),
      name: String(join('.', name)),
      isChecked: Boolean(pathOr(defaultValue, name, data))
    };
  }

  /**
   * Generate a function that will send a standard form UPDATE message to a machine
   * @param name of the field to update
   * @param send - a function that sends events to the machine
   * @returns {(function(*): void)|*}
   */
  static handleUpdate(name, send, type = 'UPDATE') {
    return (eventOrValue) => {
      let value;
      if (typeof eventOrValue === 'string') {
        value = eventOrValue;
      }
      else if (eventOrValue.currentTarget.type === 'checkbox') {
        value = eventOrValue.currentTarget.checked
      }
      else {
        value = eventOrValue.currentTarget.value;
      }
      send({
        type,
        name,
        value
      });
    };
  }

    /**
   * @param isPristine
   * @param isInvalid
   * @return {string|null} the tooltip explaining why the save button is disabled
   */
  static saveTooltip({isPristine, isInvalid}) {
    if (isPristine) {
      return UIStrings.PRISTINE_TOOLTIP;
    }
    else if (isInvalid) {
      return UIStrings.INVALID_TOOLTIP;
    }
    return null;
  }

  static discardTooltip({isPristine}) {
    if (isPristine) {
      return UIStrings.PRISTINE_TOOLTIP;
    }
  }
}
