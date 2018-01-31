package de.ugoe.cs.bugfixtypes;/*
 * Copyright (C) 2017 University of Goettingen, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public enum InterfaceChangeTypes {
    ADDITIONAL_CLASS,
    CLASS_RENAMING,
    DECREASING_ACCESSIBILITY_CHANGE,
    INCREASING_ACCESSIBILITY_CHANGE,
    METHOD_RENAMING,
    PARAMETER_DELETE,
    PARAMETER_INSERT,
    PARAMETER_ORDERING_CHANGE,
    PARAMETER_RENAMING,
    PARAMETER_TYPE_CHANGE,
    PARENT_INTERFACE_CHANGE,
    PARENT_INTERFACE_DELETE,
    PARENT_INTERFACE_INSERT,
    REMOVED_CLASS,
    RETURN_TYPE_CHANGE,
    RETURN_TYPE_DELETE,
    RETURN_TYPE_INSERT,
    ADDING_CLASS_DERIVABILITY,
    REMOVING_CLASS_DERIVABILITY,
    ADDING_METHOD_OVERRIDABILITY,
    REMOVING_METHOD_OVERRIDABILITY,
    PARENT_CLASS_CHANGE,
    PARENT_CLASS_DELETE,
    PARENT_CLASS_INSERT,
}
