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
package org.apache.joshua.decoder.ff.lm;

import org.apache.joshua.corpus.Vocabulary;
import org.apache.joshua.decoder.ff.lm.NGramLanguageModel;
import org.apache.joshua.decoder.ff.state_maintenance.KenLMState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI wrapper for KenLM. This version of KenLM supports two use cases, implemented by the separate
 * feature functions KenLMFF and LanguageModelFF. KenLMFF uses the RuleScore() interface in
 * lm/left.hh, returning a state pointer representing the KenLM state, while LangaugeModelFF handles
 * state by itself and just passes in the ngrams for scoring.
 * 
 * @author Kenneth Heafield
 * @author Matt Post post@cs.jhu.edu
 */

public class KenLM implements NGramLanguageModel, Comparable<KenLM> {

  private static final Logger LOG = LoggerFactory.getLogger(KenLM.class);

  static {
    try {
      System.loadLibrary("ken");
    } catch (UnsatisfiedLinkError e) {
      //TODO: send these prints to LOG.err
      LOG.error("* FATAL: Can't find libken.so (libken.dylib on OS X) in $JOSHUA/lib");
      LOG.error("*        This probably means that the KenLM library didn't compile.");
      LOG.error("*        Make sure that BOOST_ROOT is set to the root of your boost");
      LOG.error("*        installation (it's not /opt/local/, the default), change to");
      LOG.error("*        $JOSHUA, and type 'ant kenlm'. If problems persist, see the");
      LOG.error("*        website (joshua-decoder.org)."); //FIXME: update link to newer url
      throw new RuntimeException(e);
    }
  }

  private final long pointer;

  // this is read from the config file, used to set maximum order
  private final int ngramOrder;
  // inferred from model file (may be larger than ngramOrder)
  private final int N;
  // whether left-state minimization was requested
  private boolean minimizing;

  private final static native long construct(String file_name);

  private final static native void destroy(long ptr);

  private final static native int order(long ptr);

  private final static native boolean registerWord(long ptr, String word, int id);

  private final static native float prob(long ptr, int words[]);

  private final static native float probForString(long ptr, String[] words);

  private final static native boolean isKnownWord(long ptr, String word);

  private final static native StateProbPair probRule(long ptr, long pool, long words[]);
  
  private final static native float estimateRule(long ptr, long words[]);

  private final static native float probString(long ptr, int words[], int start);

  public final static native long createPool();
  public final static native void destroyPool(long pointer);

  public KenLM(int order, String file_name) {
    ngramOrder = order;

    pointer = construct(file_name);
    N = order(pointer);
  }

  /**
   * Constructor if order is not known.
   * Order will be inferred from the model.
   * @param file_name string path to an input file
   */
  public KenLM(String file_name) {
    pointer = construct(file_name);
    N = order(pointer);
    ngramOrder = N;
  }

  public void destroy() {
    destroy(pointer);
  }

  public int getOrder() {
    return ngramOrder;
  }

  public boolean registerWord(String word, int id) {
    return registerWord(pointer, word, id);
  }

  public float prob(int[] words) {
    return prob(pointer, words);
  }

  /**
   * Query for n-gram probability using strings.
   * @param words a string array of words
   * @return float value denoting probability
   */
  public float prob(String[] words) {
    return probForString(pointer, words);
  }

  // Apparently Zhifei starts some array indices at 1. Change to 0-indexing.
  public float probString(int words[], int start) {
    return probString(pointer, words, start - 1);
  }

  /**
   * This function is the bridge to the interface in kenlm/lm/left.hh, which has KenLM score the
   * whole rule. It takes an array of words and states retrieved from tail nodes (nonterminals in the
   * rule). Nonterminals have a negative value so KenLM can distinguish them. The sentence number is
   * needed so KenLM knows which memory pool to use. When finished, it returns the updated KenLM
   * state and the LM probability incurred along this rule.
   * 
   * @param words array of words
   * @param poolPointer todo
   * @return the updated {@link org.apache.joshua.decoder.ff.lm.KenLM.StateProbPair} e.g. 
   * KenLM state and the LM probability incurred along this rule
   */
  public StateProbPair probRule(long[] words, long poolPointer) {

    StateProbPair pair = null;
    try {
      pair = probRule(pointer, poolPointer, words);
    } catch (NoSuchMethodError e) {
      e.printStackTrace();
      System.exit(1);
    }

    return pair;
  }

  /**
   * Public facing function that estimates the cost of a rule, which value is used for sorting
   * rules during cube pruning.
   * 
   * @param words array of words
   * @return the estimated cost of the rule (the (partial) n-gram probabilities of all words in the rule)
   */
  public float estimateRule(long[] words) {
    float estimate = 0.0f;
    try {
      estimate = estimateRule(pointer, words);
    } catch (NoSuchMethodError e) {
      throw new RuntimeException(e);
    }
    
    return estimate;
  }

  /**
   * The start symbol for a KenLM is the Vocabulary.START_SYM.
   * @return "&lt;s&gt;"
   */
  public String getStartSymbol() {
    return Vocabulary.START_SYM;
  }

  public boolean isKnownWord(String word) {
    return isKnownWord(pointer, word);
  }


  /**
   * Inner class used to hold the results returned from KenLM with left-state minimization. Note
   * that inner classes have to be static to be accessible from the JNI!
   */
  public static class StateProbPair {
    public KenLMState state = null;
    public float prob = 0.0f;

    public StateProbPair(long state, float prob) {
      this.state = new KenLMState(state);
      this.prob = prob;
    }
  }

  @Override
  public int compareTo(KenLM other) {
    if (this == other)
      return 0;
    else
      return -1;
  }

  /**
   * These functions are used if KenLM is invoked under LanguageModelFF instead of KenLMFF.
   */
  @Override
  public float sentenceLogProbability(int[] sentence, int order, int startIndex) {
    return probString(sentence, startIndex);
  }

  @Override
  public float ngramLogProbability(int[] ngram, int order) {
    if (order != N && order != ngram.length)
      throw new RuntimeException("Lower order not supported.");
    return prob(ngram);
  }

  @Override
  public float ngramLogProbability(int[] ngram) {
    return prob(ngram);
  }
}
