/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.joshua.decoder.phrase;

import java.util.List;	

import org.apache.joshua.corpus.Vocabulary;
import org.apache.joshua.decoder.ff.state_maintenance.DPState;
import org.apache.joshua.decoder.ff.tm.Rule;
import org.apache.joshua.decoder.ff.tm.format.HieroFormatReader;
import org.apache.joshua.decoder.hypergraph.HGNode;
import org.apache.joshua.decoder.hypergraph.HyperEdge;

/**
 * Represents a hypothesis, a translation of some coverage of the input. Extends {@link org.apache.joshua.decoder.hypergraph.HGNode}, 
 * through a bit of a hack. Whereas (i,j) represents the span of an {@link org.apache.joshua.decoder.hypergraph.HGNode}, i here is not used,
 * and j is overloaded to denote the span of the phrase being applied. The complete coverage vector 
 * can be obtained by looking at the tail pointer and casting it.
 * 
 * @author Kenneth Heafield
 * @author Matt Post post@cs.jhu.edu
 */
public class Hypothesis extends HGNode implements Comparable<Hypothesis> {

  // The hypothesis' coverage vector
  private Coverage coverage;

  public static Rule BEGIN_RULE = new HieroFormatReader().parseLine("[X] ||| <s> ||| <s> |||   ||| 0-0");
  public static Rule END_RULE = new HieroFormatReader().parseLine("[GOAL] ||| [X,1] </s> ||| [X,1] </s> |||   ||| 0-0 1-1");

  public String toString() {
    StringBuffer sb = new StringBuffer();
    for (DPState state: getDPStates())
      sb.append(state);
    String words = bestHyperedge.getRule().getEnglishWords();
//  return String.format("HYP[%s] %.5f j=%d words=%s state=%s", coverage, score, j, words, sb);
    return String.format("HYP[%s] j=%d words=[%s] state=%s", coverage, j, words, sb);
  }

  // Initialize root hypothesis. Provide the LM's BeginSentence.
  public Hypothesis(List<DPState> states, float futureCost) {
    super(0, 1, Vocabulary.id("[X]"), states,
        new HyperEdge(BEGIN_RULE, 0.0f, 0.0f, null, null), futureCost);
    this.coverage = new Coverage(1);
  }

  public Hypothesis(Candidate cand) {
    // TODO: sourcepath
    super(-1, cand.span.end, Vocabulary.id("[X]"), cand.getStates(), new HyperEdge(
        cand.getRule(), cand.getResult().getViterbiCost(), cand.getResult().getTransitionCost(),
        cand.getTailNodes(), null), cand.score());
    this.coverage = cand.getCoverage();
  }
  
  // Extend a previous hypothesis.
  public Hypothesis(List<DPState> states, float score, Hypothesis previous, int source_end, Rule target) {
    super(-1, source_end, -1, null, null, score);
    this.coverage = previous.coverage;
  }

  public Coverage getCoverage() {
    return coverage;
  }

  public Rule getRule() {
    return bestHyperedge.getRule();
  }

  /**
   * HGNodes (designed for chart parsing) maintain a span (i,j). We overload j
   * here to record the index of the last translated source word.
   * 
   * @return the int 'j' which is overloaded to denote the span of the phrase being applied
   */
  public int LastSourceIndex() {
    return j;
  }

  @Override
  public int hashCode() {
    int hash = 0;
    hash = 31 * LastSourceIndex() + 19 * getCoverage().hashCode();
    if (null != dpStates && dpStates.size() > 0)
      for (DPState dps: dpStates)
        hash *= 57 + dps.hashCode();
    return hash;
  }

  /**
   * Defines equivalence in terms of recombinability. Two hypotheses are recombinable if 
   * all their DP states are the same, their coverage is the same, and they have the next soure
   * index the same.
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Hypothesis) {
      Hypothesis other = (Hypothesis) obj;

      if (LastSourceIndex() != other.LastSourceIndex() || ! getCoverage().equals(other.getCoverage()))
        return false;
      
      if (dpStates == null)
        return (other.dpStates == null);
      
      if (other.dpStates == null)
        return false;
      
      if (dpStates.size() != other.dpStates.size())
        return false;
      
      for (int i = 0; i < dpStates.size(); i++) {
        if (!dpStates.get(i).equals(other.dpStates.get(i)))
          return false;
      }
      
      return true;
    }
    return false;
  }

  @Override
  public int compareTo(Hypothesis o) {
    // TODO: is this the order we want?
    return Float.compare(o.getScore(), getScore());
  }

  /**
   * Performs hypothesis recombination, incorporating the incoming hyperedges of the added
   * hypothesis and possibly updating the cache of the best incoming hyperedge and score.
   * 
   * @param added the equivalent hypothesis 
   */
  public void absorb(Hypothesis added) {
    assert(this.equals(added));
    score = Math.max(score, added.getScore());
    addHyperedgesInNode(added.hyperedges);
  }
}
