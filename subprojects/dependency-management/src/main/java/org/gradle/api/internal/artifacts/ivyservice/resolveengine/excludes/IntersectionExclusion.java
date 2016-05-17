/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes;

import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.internal.component.model.IvyArtifactName;

import java.util.*;

/**
 * A spec that excludes modules or artifacts that are excluded by _any_ of the supplied exclusions.
 * As such, this is an intersection of the separate exclude rule filters.
 */
class IntersectionExclusion extends AbstractCompositeExclusion {
    private final Set<AbstractModuleExclusion> excludeSpecs = new HashSet<AbstractModuleExclusion>();

    public IntersectionExclusion(Collection<AbstractModuleExclusion> specs) {
        this.excludeSpecs.addAll(specs);
    }

    Collection<AbstractModuleExclusion> getFilters() {
        return excludeSpecs;
    }

    @Override
    protected boolean excludesNoModules() {
        for (AbstractModuleExclusion excludeSpec : excludeSpecs) {
            if (!excludeSpec.excludesNoModules()) {
                return false;
            }
        }
        return true;
    }

    public boolean excludeModule(ModuleIdentifier element) {
        for (AbstractModuleExclusion excludeSpec : excludeSpecs) {
            if (excludeSpec.excludeModule(element)) {
                return true;
            }
        }
        return false;
    }

    public boolean excludeArtifact(ModuleIdentifier module, IvyArtifactName artifact) {
        for (AbstractModuleExclusion excludeSpec : excludeSpecs) {
            if (excludeSpec.excludeArtifact(module, artifact)) {
                return true;
            }
        }
        return false;
    }

    public boolean mayExcludeArtifacts() {
        for (AbstractModuleExclusion spec : excludeSpecs) {
            if (spec.mayExcludeArtifacts()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Can unpack into constituents when creating a larger intersection (since elements are applied as an intersection).
     */
    @Override
    protected void unpackIntersection(Collection<AbstractModuleExclusion> specs) {
        specs.addAll(excludeSpecs);
    }

    /**
     * Construct a single spec that will exclude any module/artifact that is excluded by both this _and_ the other filter.
     *
     * Returns null when this union cannot be computed. This will happen if 'other' is _not_ an Intersection itself,
     * or if any of the consituent specs cannot be merged.
     */
    @Override
    protected AbstractModuleExclusion maybeMergeIntoUnion(AbstractModuleExclusion other) {
        if (!(other instanceof IntersectionExclusion)) {
            return null;
        }

        IntersectionExclusion multipleExcludeRulesSpec = (IntersectionExclusion) other;
        if (excludeSpecs.equals(multipleExcludeRulesSpec.excludeSpecs)) {
            return this;
        }

        // Can only merge exact match rules, so don't try if this or the other spec contains any other type of rule
        for (AbstractModuleExclusion excludeSpec : excludeSpecs) {
            if (!canMerge(excludeSpec)) {
                return null;
            }
        }
        for (AbstractModuleExclusion excludeSpec : multipleExcludeRulesSpec.excludeSpecs) {
            if (!canMerge(excludeSpec)) {
                return null;
            }
        }

        // Merge the exclude rules from both specs into a single union spec.
        List<AbstractModuleExclusion> merged = new ArrayList<AbstractModuleExclusion>();
        for (AbstractModuleExclusion thisSpec : excludeSpecs) {
            for (AbstractModuleExclusion otherSpec : multipleExcludeRulesSpec.excludeSpecs) {
                mergeExcludeRules(thisSpec, otherSpec, merged);
            }
        }
        if (merged.isEmpty()) {
            return ModuleExclusions.EXCLUDE_NONE;
        }
        return new IntersectionExclusion(merged);
    }

    private boolean canMerge(AbstractModuleExclusion excludeSpec) {
        return excludeSpec instanceof ExcludeAllModulesSpec
            || excludeSpec instanceof ArtifactExcludeSpec
            || excludeSpec instanceof GroupNameExcludeSpec
            || excludeSpec instanceof ModuleNameExcludeSpec
            || excludeSpec instanceof ModuleIdExcludeSpec;
    }

    // Add exclusions to the list that will exclude modules/artifacts that are excluded by _both_ of the candidate rules.
    private void mergeExcludeRules(AbstractModuleExclusion spec1, AbstractModuleExclusion spec2, List<AbstractModuleExclusion> merged) {
        if (spec1 instanceof ExcludeAllModulesSpec) {
            // spec1 excludes everything: use spec2 excludes
            merged.add(spec2);
        } else if (spec2 instanceof ExcludeAllModulesSpec) {
            // spec2 excludes everything: use spec1 excludes
            merged.add(spec1);
        } else if (spec1 instanceof ArtifactExcludeSpec) {
            // Excludes _no_ modules, may exclude some artifacts.
            // This isn't right: We are losing the artifacts excluded by spec2
            // (2 artifact excludes should cancel out unless equal)
            merged.add(spec1);
        } else if (spec2 instanceof ArtifactExcludeSpec) {
            // Excludes _no_ modules, may exclude some artifacts.
            // This isn't right: We are losing the artifacts excluded by spec2
            merged.add(spec2);
        } else if (spec1 instanceof GroupNameExcludeSpec) {
            // Merge into a single exclusion for Group + Module
            mergeExcludeRules((GroupNameExcludeSpec) spec1, spec2, merged);
        } else if (spec2 instanceof GroupNameExcludeSpec) {
            // Merge into a single exclusion for Group + Module
            mergeExcludeRules((GroupNameExcludeSpec) spec2, spec1, merged);
        } else if (spec1 instanceof ModuleNameExcludeSpec) {
            // Merge into a single exclusion for Group + Module
            mergeExcludeRules((ModuleNameExcludeSpec) spec1, spec2, merged);
        } else if (spec2 instanceof ModuleNameExcludeSpec) {
            // Merge into a single exclusion for Group + Module
            mergeExcludeRules((ModuleNameExcludeSpec) spec2, spec1, merged);
        } else if ((spec1 instanceof ModuleIdExcludeSpec) && (spec2 instanceof ModuleIdExcludeSpec)) {
            // Excludes nothing if the excluded module ids don't match: in that case this rule contributes nothing to the union
            ModuleIdExcludeSpec moduleSpec1 = (ModuleIdExcludeSpec) spec1;
            ModuleIdExcludeSpec moduleSpec2 = (ModuleIdExcludeSpec) spec2;
            if (moduleSpec1.moduleId.equals(moduleSpec2.moduleId)) {
                merged.add(moduleSpec1);
            }
        } else {
            throw new UnsupportedOperationException(String.format("Cannot calculate intersection of exclude rules: %s, %s", spec1, spec2));
        }
    }

    private void mergeExcludeRules(GroupNameExcludeSpec spec1, AbstractModuleExclusion spec2, List<AbstractModuleExclusion> merged) {
        if (spec2 instanceof GroupNameExcludeSpec) {
            // Intersection of 2 group excludes does nothing unless excluded groups match
            GroupNameExcludeSpec groupNameExcludeSpec = (GroupNameExcludeSpec) spec2;
            if (spec1.group.equals(groupNameExcludeSpec.group)) {
                merged.add(spec1);
            }
        } else if (spec2 instanceof ModuleNameExcludeSpec) {
            // Intersection of group & module name exclude only excludes module with matching group + name
            ModuleNameExcludeSpec moduleNameExcludeSpec = (ModuleNameExcludeSpec) spec2;
            merged.add(new ModuleIdExcludeSpec(spec1.group, moduleNameExcludeSpec.module));
        } else if (spec2 instanceof ModuleIdExcludeSpec) {
            // Intersection of group + module id exclude only excludes the module id if the excluded groups match
            ModuleIdExcludeSpec moduleIdExcludeSpec = (ModuleIdExcludeSpec) spec2;
            if (moduleIdExcludeSpec.moduleId.getGroup().equals(spec1.group)) {
                merged.add(spec2);
            }
        } else {
            throw new UnsupportedOperationException(String.format("Cannot calculate intersection of exclude rules: %s, %s", spec1, spec2));
         }
    }

    private void mergeExcludeRules(ModuleNameExcludeSpec spec1, AbstractModuleExclusion spec2, List<AbstractModuleExclusion> merged) {
        if (spec2 instanceof ModuleNameExcludeSpec) {
            // Intersection of 2 module name excludes does nothing unless excluded module names match
            ModuleNameExcludeSpec moduleNameExcludeSpec = (ModuleNameExcludeSpec) spec2;
            if (spec1.module.equals(moduleNameExcludeSpec.module)) {
                merged.add(spec1);
            }
        } else if (spec2 instanceof ModuleIdExcludeSpec) {
            // Intersection of module name & module id exclude only excludes module if the excluded module names match
            ModuleIdExcludeSpec moduleIdExcludeSpec = (ModuleIdExcludeSpec) spec2;
            if (moduleIdExcludeSpec.moduleId.getName().equals(spec1.module)) {
                merged.add(spec2);
            }
        } else {
            throw new UnsupportedOperationException(String.format("Cannot calculate intersection of exclude rules: %s, %s", spec1, spec2));
        }
    }
}