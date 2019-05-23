/*
 * Copyright 2016 Ecole des Mines de Saint-Etienne.
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
package fr.emse.ci.sparqlext.generate.engine;

import fr.emse.ci.sparqlext.SPARQLExt;
import fr.emse.ci.sparqlext.SPARQLExtException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingHashMap;
import org.apache.jena.sparql.syntax.ElementData;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.util.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the generated SPARQL SELECT query.
 *
 * @author Maxime Lefrançois <maxime.lefrancois at emse.fr>
 */
public class SelectPlan {

    private static final Logger LOG = LoggerFactory.getLogger(SelectPlan.class);

    /**
     * The query.
     */
    public final Query select;

    private final boolean isSelectType;

    /**
     * Constructor.
     *
     * @param query the SPARQL SELECT query.
     */
    public SelectPlan(final Query query, final boolean isSelectType) {
        if (!query.isSelectType()) {
            LOG.error("Should be select query. " + query);
            throw new IllegalArgumentException("Should be select query.");
        }
        this.select = query;
        this.isSelectType = isSelectType;
    }

    public List<Var> getVars() {
        return select.getProjectVars();
    }

    /**
     * Updates a values block with the execution of a SPARQL SELECT query.
     *
     * @param inputDataset the Dataset to use for the SPARQL SELECT part of the
     * query.
     * @param variables the variables
     * @param values the list of bindings.
     * @param context the execution context.
     * @return the new list of bindings
     */
    final public ResultSet exec(
            final Dataset inputDataset,
            final List<Var> variables,
            final List<Binding> values,
            final Context context) {
        if (Thread.interrupted()) {
            throw new SPARQLExtException(new InterruptedException());
        }
        LOG.debug("Executing select with " + values.size() + " bindings");
        final Query q = createQuery(select, variables, values);
        if (LOG.isTraceEnabled()) {
            LOG.trace("Query is\n" + q
                    + " \ninitial values are:\n" + SPARQLExt.log(variables, values));
        }
        try {
            augmentQuery(q, variables, values);
            if (LOG.isTraceEnabled()) {
                final QueryExecution exec = QueryExecutionFactory.create(q, inputDataset);
                exec.getContext().putAll(context);
                ResultSet result = exec.execSelect();
                final List<Var> resultVariables = SPARQLExt.getVariables(result.getResultVars(), context);
                final List<Binding> resultBindings = new ArrayList<>();
                while (result.hasNext()) {
                    resultBindings.add(result.nextBinding());
                }
                LOG.debug("Query output is\n" + SPARQLExt.log(resultVariables, resultBindings));
            }
            final QueryExecution exec = QueryExecutionFactory.create(q, inputDataset);
            exec.getContext().putAll(context);
            return exec.execSelect();
        } catch (Exception ex) {
            LOG.error("Error while executing SELECT Query " + q, ex);
            throw new SPARQLExtException("Error while executing SELECT "
                    + "Query" + q, ex);
        }

    }

    private Query createQuery(
            final Query select,
            final List<Var> variables,
            final List<Binding> values) {
        Query q = select.cloneQuery();
        if (!isSelectType && !q.hasGroupBy() && !q.hasAggregators()) {
            variables.forEach(v -> {
                if (!q.getProjectVars().contains(v)) {
                    q.getProject().add(v);
                }
            });
        }
        return q;
    }

    private void augmentQuery(
            final Query q,
            final List<Var> variables,
            final List<Binding> values) {
        ElementGroup old = (ElementGroup) q.getQueryPattern();
        ElementGroup newQueryPattern = new ElementGroup();
        q.setQueryPattern(newQueryPattern);
        if (old.size() > 1 && old.get(0) instanceof ElementData) {
            ElementData qData = (ElementData) old.get(0);
            int oldSize = qData.getRows().size();
            qData = mergeValues(qData, variables, values);
            newQueryPattern.addElement(qData);
            for (int i = 1; i < old.size(); i++) {
                newQueryPattern.addElement(old.get(i));
            }
            LOG.debug("New query has " + qData.getRows().size() + " initial values. It had " + oldSize + " values before");
        } else {
            ElementData data = new ElementData();
            variables.forEach(data::add);
            values.forEach(data::add);
            newQueryPattern.addElement(data);
            old.getElements().forEach(newQueryPattern::addElement);
            LOG.debug("New query has " + data.getRows().size() + " initial values");
            // unexplainable, but did happen
            check(data, values);
        }
    }

    private ElementData mergeValues(
            final ElementData qData,
            final List<Var> variables,
            final List<Binding> values) {
        if (values.isEmpty()) {
            return qData;
        }
        List<Var> vars = qData.getVars();
        if (!Collections.disjoint(vars, variables)) {
            throw new SPARQLExtException("Variables " + vars.retainAll(variables) + "were already bound.");
        }
        ElementData data = new ElementData();
        qData.getVars().forEach(data::add);
        variables.forEach(data::add);
        qData.getRows().forEach((qbinding) -> {
            values.forEach((binding) -> {
                BindingHashMap newb = new BindingHashMap(qbinding);
                variables.forEach((v) -> newb.add(v, binding.get(v)));
                data.add(newb);
            });
        });
        return data;
    }

    private void check(ElementData data, Collection<Binding> values) {
        if (data.getRows().size() != values.size()) {
            LOG.warn("Different size for the values block here.\n Was "
                    + values.size() + ": \n" + values + "\n now is "
                    + data.getRows().size() + ": \n" + data.getRows());
            StringBuilder sb = new StringBuilder("Different size for the values block here.\n Was "
                    + values.size() + ": \n" + values + "\n\n");
            int i = 0;
            for (Binding b : values) {
                sb.append("\nbinding ").append(i++).append(" is ").append(b);
            }
            LOG.warn(sb.toString());
        }
    }
}
