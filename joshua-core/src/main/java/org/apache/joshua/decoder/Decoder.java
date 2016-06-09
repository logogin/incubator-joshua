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
package org.apache.joshua.decoder;

import static org.apache.joshua.decoder.ff.FeatureVector.DENSE_FEATURE_NAMES;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.google.common.base.Strings;

import org.apache.joshua.corpus.Vocabulary;
import org.apache.joshua.decoder.ff.FeatureVector;
import org.apache.joshua.decoder.ff.FeatureFunction;
import org.apache.joshua.decoder.ff.PhraseModel;
import org.apache.joshua.decoder.ff.StatefulFF;
import org.apache.joshua.decoder.ff.lm.LanguageModelFF;
import org.apache.joshua.decoder.ff.tm.Grammar;
import org.apache.joshua.decoder.ff.tm.Rule;
import org.apache.joshua.decoder.ff.tm.Trie;
import org.apache.joshua.decoder.ff.tm.format.HieroFormatReader;
import org.apache.joshua.decoder.ff.tm.hash_based.MemoryBasedBatchGrammar;
import org.apache.joshua.decoder.ff.tm.packed.PackedGrammar;
import org.apache.joshua.decoder.io.JSONMessage;
import org.apache.joshua.decoder.io.TranslationRequestStream;
import org.apache.joshua.decoder.phrase.PhraseTable;
import org.apache.joshua.decoder.segment_file.Sentence;
import org.apache.joshua.util.FileUtility;
import org.apache.joshua.util.FormatUtils;
import org.apache.joshua.util.Regex;
import org.apache.joshua.util.io.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class handles decoder initialization and the complication introduced by multithreading.
 *
 * After initialization, the main entry point to the Decoder object is
 * decodeAll(TranslationRequest), which returns a set of Translation objects wrapped in an iterable
 * Translations object. It is important that we support multithreading both (a) across the sentences
 * within a request and (b) across requests, in a round-robin fashion. This is done by maintaining a
 * fixed sized concurrent thread pool. When a new request comes in, a RequestParallelizer thread is
 * launched. This object iterates over the request's sentences, obtaining a thread from the
 * thread pool, and using that thread to decode the sentence. If a decoding thread is not available,
 * it will block until one is in a fair (FIFO) manner. RequestParallelizer thereby permits intra-request
 * parallelization by separating out reading the input stream from processing the translated sentences,
 * but also ensures that round-robin parallelization occurs, since RequestParallelizer uses the
 * thread pool before translating each request.
 *
 * A decoding thread is handled by DecoderThread and launched from DecoderThreadRunner. The purpose
 * of the runner is to record where to place the translated sentence when it is done (i.e., which
 * Translations object). Translations itself is an iterator whose next() call blocks until the next
 * translation is available.
 *
 * @author Matt Post post@cs.jhu.edu
 * @author Zhifei Li, zhifei.work@gmail.com
 * @author wren ng thornton wren@users.sourceforge.net
 * @author Lane Schwartz dowobeha@users.sourceforge.net
 */
public class Decoder {

  private static final Logger LOG = LoggerFactory.getLogger(Decoder.class);

  private final JoshuaConfiguration joshuaConfiguration;

  public JoshuaConfiguration getJoshuaConfiguration() {
    return joshuaConfiguration;
  }

  /*
   * Many of these objects themselves are global objects. We pass them in when constructing other
   * objects, so that they all share pointers to the same object. This is good because it reduces
   * overhead, but it can be problematic because of unseen dependencies (for example, in the
   * Vocabulary shared by language model, translation grammar, etc).
   */
  private List<Grammar> grammars;
  private ArrayList<FeatureFunction> featureFunctions;
  private Grammar customPhraseTable;

  /* The feature weights. */
  public static FeatureVector weights;

  public static int VERBOSE = 1;

  private BlockingQueue<DecoderThread> threadPool = null;

  // ===============================================================
  // Constructors
  // ===============================================================

  /**
   * Constructor method that creates a new decoder using the specified configuration file.
   *
   * @param joshuaConfiguration a populated {@link org.apache.joshua.decoder.JoshuaConfiguration}
   * @param configFile name of configuration file.
   */
  public Decoder(JoshuaConfiguration joshuaConfiguration, String configFile) {
    this(joshuaConfiguration);
    this.initialize(configFile);
  }

  /**
   * Factory method that creates a new decoder using the specified configuration file.
   *
   * @param configFile Name of configuration file.
   * @return a configured {@link org.apache.joshua.decoder.Decoder}
   */
  public static Decoder createDecoder(String configFile) {
    JoshuaConfiguration joshuaConfiguration = new JoshuaConfiguration();
    return new Decoder(joshuaConfiguration, configFile);
  }

  /**
   * Constructs an uninitialized decoder for use in testing.
   * <p>
   * This method is private because it should only ever be called by the
   * {@link #getUninitalizedDecoder()} method to provide an uninitialized decoder for use in
   * testing.
   */
  private Decoder(JoshuaConfiguration joshuaConfiguration) {
    this.joshuaConfiguration = joshuaConfiguration;
    this.grammars = new ArrayList<Grammar>();
    this.threadPool = new ArrayBlockingQueue<DecoderThread>(
        this.joshuaConfiguration.num_parallel_decoders, true);
    this.customPhraseTable = null;
  }

  /**
   * Gets an uninitialized decoder for use in testing.
   * <p>
   * This method is called by unit tests or any outside packages (e.g., MERT) relying on the
   * decoder.
   * @param joshuaConfiguration a {@link org.apache.joshua.decoder.JoshuaConfiguration} object
   * @return an uninitialized decoder for use in testing
   */
  static public Decoder getUninitalizedDecoder(JoshuaConfiguration joshuaConfiguration) {
    return new Decoder(joshuaConfiguration);
  }

  // ===============================================================
  // Public Methods
  // ===============================================================

  /**
   * This class is responsible for getting sentences from the TranslationRequest and procuring a
   * DecoderThreadRunner to translate it. Each call to decodeAll(TranslationRequest) launches a
   * thread that will read the request's sentences, obtain a DecoderThread to translate them, and
   * then place the Translation in the appropriate place.
   *
   * @author Matt Post <post@cs.jhu.edu>
   *
   */
  private class RequestParallelizer extends Thread {
    /* Source of sentences to translate. */
    private final TranslationRequestStream request;

    /* Where to put translated sentences. */
    private final Translations response;

    RequestParallelizer(TranslationRequestStream request, Translations response) {
      this.request = request;
      this.response = response;
    }

    @Override
    public void run() {
      /*
       * Repeatedly get an input sentence, wait for a DecoderThread, and then start a new thread to
       * translate the sentence. We start a new thread (via DecoderRunnerThread) as opposed to
       * blocking, so that the RequestHandler can go on to the next sentence in this request, which
       * allows parallelization across the sentences of the request.
       */
      for (;;) {
        Sentence sentence = request.next();

        if (sentence == null) {
          response.finish();
          break;
        }

        // This will block until a DecoderThread becomes available.
        DecoderThread thread = Decoder.this.getThread();
        new DecoderThreadRunner(thread, sentence, response).start();
      }
    }

    /**
     * Strips the nonterminals from the lefthand side of the rule.
     *
     * @param rule
     * @return
     */
    private String formatRule(Rule rule) {
      String ruleString = "";
      boolean first = true;
      for (int word: rule.getFrench()) {
        if (!first)
          ruleString += " " + Vocabulary.word(word);
        first = false;
      }

      ruleString += " |||"; // space will get added with first English word
      first = true;
      for (int word: rule.getEnglish()) {
        if (!first)
          ruleString += " " + Vocabulary.word(word);
        first = false;
      }

      // strip of the leading space
      return ruleString.substring(1);
    }
  }

  /**
   * Retrieve a thread from the thread pool, blocking until one is available. The blocking occurs in
   * a fair fashion (i.e,. FIFO across requests).
   *
   * @return a thread that can be used for decoding.
   */
  public DecoderThread getThread() {
    try {
      return threadPool.take();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  /**
   * When metadata is found on the input, it needs to be processed. That is done here. Sometimes
   * this involves returning data to the client.
   *
   * @param meta
   * @throws IOException
   */
  private void handleMetadata(MetaData meta) {
    if (meta.type().equals("set_weights")) {
      // Change a decoder weight
      String[] args = meta.tokens();
      for (int i = 0; i < args.length; i += 2) {
        float old_weight = Decoder.weights.getWeight(args[i]);
        Decoder.weights.set(args[1], Float.parseFloat(args[i+1]));
        LOG.error("@set_weights: {} {} -> {}", args[1], old_weight,
            Decoder.weights.getWeight(args[0]));
      }

    } else if (meta.type().equals("add_rule")) {
      String args[] = meta.tokens(" ,,, ");
  
      if (args.length != 2) {
        LOG.error("* INVALID RULE '{}'", meta);
        return;
      }
      
      String source = args[0];
      String target = args[1];
      String featureStr = "";
      if (args.length > 2) 
        featureStr = args[2];
          

      /* Prepend source and target side nonterminals for phrase-based decoding. Probably better
       * handled in each grammar type's addRule() function.
       */
      String ruleString = (joshuaConfiguration.search_algorithm.equals("stack"))
          ? String.format("[X] ||| [X,1] %s ||| [X,1] %s ||| custom=1 %s", source, target, featureStr)
          : String.format("[X] ||| %s ||| %s ||| custom=1 %s", source, target, featureStr);
      
      Rule rule = new HieroFormatReader().parseLine(ruleString);
      Decoder.this.customPhraseTable.addRule(rule);
      rule.estimateRuleCost(featureFunctions);
      LOG.info("Added custom rule {}", rule.toString());
  
    } else if (meta.type().equals("list_rules")) {
  
      JSONMessage message = new JSONMessage();
  
      // Walk the the grammar trie
      ArrayList<Trie> nodes = new ArrayList<Trie>();
      nodes.add(customPhraseTable.getTrieRoot());
  
      while (nodes.size() > 0) {
        Trie trie = nodes.remove(0);
  
        if (trie == null)
          continue;
  
        if (trie.hasRules()) {
          for (Rule rule: trie.getRuleCollection().getRules()) {
            message.addRule(rule.toString());
          }
        }
  
        if (trie.getExtensions() != null)
          nodes.addAll(trie.getExtensions());
      }
  
    } else if (meta.type().equals("remove_rule")) {
      // Remove a rule from a custom grammar, if present
      String[] args = meta.tokenString().split(" ,,, ");
      if (args.length != 2) {
        return;
      }
  
      // Search for the rule in the trie
      int nt_i = Vocabulary.id(joshuaConfiguration.default_non_terminal);
      Trie trie = customPhraseTable.getTrieRoot().match(nt_i);
  
      for (String word: args[0].split("\\s+")) {
        int id = Vocabulary.id(word);
        Trie nextTrie = trie.match(id);
        if (nextTrie != null)
          trie = nextTrie;
      }
  
      if (trie.hasRules()) {
        Rule matched = null;
        for (Rule rule: trie.getRuleCollection().getRules()) {
          String target = rule.getEnglishWords();
          target = target.substring(target.indexOf(' ') + 1);
  
          if (args[1].equals(target)) {
            matched = rule;
            break;
          }
        }
        trie.getRuleCollection().getRules().remove(matched);
        return;
      }
    }
  }

  /**
   * This class handles running a DecoderThread (which takes care of the actual translation of an
   * input Sentence, returning a Translation object when its done). This is done in a thread so as
   * not to tie up the RequestHandler that launched it, freeing it to go on to the next sentence in
   * the TranslationRequest, in turn permitting parallelization across the sentences of a request.
   *
   * When the decoder thread is finshed, the Translation object is placed in the correct place in
   * the corresponding Translations object that was returned to the caller of
   * Decoder.decodeAll(TranslationRequest).
   *
   * @author Matt Post <post@cs.jhu.edu>
   */
  private class DecoderThreadRunner extends Thread {

    private final DecoderThread decoderThread;
    private final Sentence sentence;
    private final Translations translations;

    DecoderThreadRunner(DecoderThread thread, Sentence sentence, Translations translations) {
      this.decoderThread = thread;
      this.sentence = sentence;
      this.translations = translations;
    }

    @Override
    public void run() {
      /*
       * Process any found metadata.
       */
      
      if (sentence.hasMetaData()) {
        handleMetadata(sentence.getMetaData());
      }

      /*
       * Use the thread to translate the sentence. Then record the translation with the
       * corresponding Translations object, and return the thread to the pool.
       */
      try {
        Translation translation = decoderThread.translate(this.sentence);
        translations.record(translation);

        /*
         * This is crucial! It's what makes the thread available for the next sentence to be
         * translated.
         */
        threadPool.put(decoderThread);
      } catch (Exception e) {
        throw new RuntimeException(String.format(
            "Input %d: FATAL UNCAUGHT EXCEPTION: %s", sentence.id(), e.getMessage()), e);
        //        translations.record(new Translation(sentence, null, featureFunctions, joshuaConfiguration));
      }
    }
  }

  /**
   * This function is the main entry point into the decoder. It translates all the sentences in a
   * (possibly boundless) set of input sentences. Each request launches its own thread to read the
   * sentences of the request.
   *
   * @param request the populated {@link org.apache.joshua.decoder.io.TranslationRequestStream}
   * @throws IOException if there is an error with the input stream or writing the output
   * @return an iterable, asynchronously-filled list of Translations
   */
  public Translations decodeAll(TranslationRequestStream request) throws IOException {
    Translations translations = new Translations(request);

    /* Start a thread to handle requests on the input stream */
    new RequestParallelizer(request, translations).start();

    return translations;
  }


  /**
   * We can also just decode a single sentence.
   *
   * @param sentence {@link org.apache.joshua.lattice.Lattice} input
   * @return the sentence {@link org.apache.joshua.decoder.Translation}
   */
  public Translation decode(Sentence sentence) {
    // Get a thread.

    try {
      DecoderThread thread = threadPool.take();
      Translation translation = thread.translate(sentence);
      threadPool.put(thread);

      return translation;

    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    return null;
  }

  /**
   * Clean shutdown of Decoder, resetting all
   * static variables, such that any other instance of Decoder
   * afterwards gets a fresh start.
   */
  public void cleanUp() {
    // shut down DecoderThreads
    for (DecoderThread thread : threadPool) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    resetGlobalState();
  }

  public static void resetGlobalState() {
    // clear/reset static variables
    DENSE_FEATURE_NAMES.clear();
    Vocabulary.clear();
    Vocabulary.unregisterLanguageModels();
    LanguageModelFF.resetLmIndex();
    StatefulFF.resetGlobalStateIndex();
  }

  public static void writeConfigFile(double[] newWeights, String template, String outputFile,
      String newDiscriminativeModel) {
    try {
      int columnID = 0;

      BufferedWriter writer = FileUtility.getWriteFileStream(outputFile);
      LineReader reader = new LineReader(template);
      try {
        for (String line : reader) {
          line = line.trim();
          if (Regex.commentOrEmptyLine.matches(line) || line.indexOf("=") != -1) {
            // comment, empty line, or parameter lines: just copy
            writer.write(line);
            writer.newLine();

          } else { // models: replace the weight
            String[] fds = Regex.spaces.split(line);
            StringBuffer newSent = new StringBuffer();
            if (!Regex.floatingNumber.matches(fds[fds.length - 1])) {
              throw new IllegalArgumentException("last field is not a number; the field is: "
                  + fds[fds.length - 1]);
            }

            if (newDiscriminativeModel != null && "discriminative".equals(fds[0])) {
              newSent.append(fds[0]).append(' ');
              newSent.append(newDiscriminativeModel).append(' ');// change the
              // file name
              for (int i = 2; i < fds.length - 1; i++) {
                newSent.append(fds[i]).append(' ');
              }
            } else {// regular
              for (int i = 0; i < fds.length - 1; i++) {
                newSent.append(fds[i]).append(' ');
              }
            }
            if (newWeights != null)
              newSent.append(newWeights[columnID++]);// change the weight
            else
              newSent.append(fds[fds.length - 1]);// do not change

            writer.write(newSent.toString());
            writer.newLine();
          }
        }
      } finally {
        reader.close();
        writer.close();
      }

      if (newWeights != null && columnID != newWeights.length) {
        throw new IllegalArgumentException("number of models does not match number of weights");
      }

    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  // ===============================================================
  // Initialization Methods
  // ===============================================================

  /**
   * Moses requires the pattern .*_.* for sparse features, and prohibits underscores in dense features. 
   * This conforms to that pattern. We assume non-conforming dense features start with tm_ or lm_,
   * and the only sparse feature that needs converting is OOVPenalty.
   *
   * @param feature
   * @return the feature in Moses format
   */
  private String mosesize(String feature) {
    if (joshuaConfiguration.moses) {
      if (feature.startsWith("tm_") || feature.startsWith("lm_"))
        return feature.replace("_", "-");
    }

    return feature;
  }

  /**
   * Initialize all parts of the JoshuaDecoder.
   *
   * @param configFile File containing configuration options
   * @return An initialized decoder
   */
  public Decoder initialize(String configFile) {
    try {

      long pre_load_time = System.currentTimeMillis();

      /* Weights can be listed in a separate file (denoted by parameter "weights-file") or directly
       * in the Joshua config file. Config file values take precedent.
       */
      this.readWeights(joshuaConfiguration.weights_file);
      
      
      /* Add command-line-passed weights to the weights array for processing below */
      if (!Strings.isNullOrEmpty(joshuaConfiguration.weight_overwrite)) {
        String[] tokens = joshuaConfiguration.weight_overwrite.split("\\s+");
        for (int i = 0; i < tokens.length; i += 2) {
          String feature = tokens[i];
          float value = Float.parseFloat(tokens[i+1]);

          if (joshuaConfiguration.moses)
            feature = demoses(feature);

          joshuaConfiguration.weights.add(String.format("%s %s", feature, tokens[i+1]));
          LOG.info("COMMAND LINE WEIGHT: {} -> {}", feature, value);
        }
      }

      /* Read the weights found in the config file */
      for (String pairStr: joshuaConfiguration.weights) {
        String pair[] = pairStr.split("\\s+");

        /* Sanity check for old-style unsupported feature invocations. */
        if (pair.length != 2) {
          StringBuilder errMsg = new StringBuilder();
          errMsg.append("FATAL: Invalid feature weight line found in config file.\n");
          errMsg.append(String.format("The line was '%s'\n", pairStr));
          errMsg.append("You might be using an old version of the config file that is no longer supported\n");
          errMsg.append("Check joshua-decoder.org or email joshua_support@googlegroups.com for help\n");
          errMsg.append("Code = " + 17);
          throw new RuntimeException(errMsg.toString());
        }

        weights.set(pair[0], Float.parseFloat(pair[1]));
      }

      LOG.info("Read {} weights ({} of them dense)", weights.size(), DENSE_FEATURE_NAMES.size());

      // Do this before loading the grammars and the LM.
      this.featureFunctions = new ArrayList<FeatureFunction>();

      // Initialize and load grammars. This must happen first, since the vocab gets defined by
      // the packed grammar (if any)
      this.initializeTranslationGrammars();
      LOG.info("Grammar loading took: {} seconds.",
          (System.currentTimeMillis() - pre_load_time) / 1000);

      // Initialize the features: requires that LM model has been initialized.
      this.initializeFeatureFunctions();

      // This is mostly for compatibility with the Moses tuning script
      if (joshuaConfiguration.show_weights_and_quit) {
        for (int i = 0; i < DENSE_FEATURE_NAMES.size(); i++) {
          String name = DENSE_FEATURE_NAMES.get(i);
          if (joshuaConfiguration.moses)
            System.out.println(String.format("%s= %.5f", mosesize(name), weights.getDense(i)));
          else
            System.out.println(String.format("%s %.5f", name, weights.getDense(i)));
        }
        System.exit(0);
      }

      // Sort the TM grammars (needed to do cube pruning)
      if (joshuaConfiguration.amortized_sorting) {
        LOG.info("Grammar sorting happening lazily on-demand.");
      } else {
        long pre_sort_time = System.currentTimeMillis();
        for (Grammar grammar : this.grammars) {
          grammar.sortGrammar(this.featureFunctions);
        }
        LOG.info("Grammar sorting took {} seconds.",
            (System.currentTimeMillis() - pre_sort_time) / 1000);
      }

      // Create the threads
      for (int i = 0; i < joshuaConfiguration.num_parallel_decoders; i++) {
        this.threadPool.put(new DecoderThread(this.grammars, Decoder.weights,
            this.featureFunctions, joshuaConfiguration));
      }
    } catch (IOException | InterruptedException e) {
      LOG.warn(e.getMessage(), e);
    }

    return this;
  }

  /**
   * Initializes translation grammars Retained for backward compatibility
   *
   * @param ownersSeen Records which PhraseModelFF's have been instantiated (one is needed for each
   *          owner)
   * @throws IOException
   */
  private void initializeTranslationGrammars() throws IOException {

    if (joshuaConfiguration.tms.size() > 0) {

      // collect packedGrammars to check if they use a shared vocabulary
      final List<PackedGrammar> packed_grammars = new ArrayList<>();

      // tm = {thrax/hiero,packed,samt,moses} OWNER LIMIT FILE
      for (String tmLine : joshuaConfiguration.tms) {

        String type = tmLine.substring(0,  tmLine.indexOf(' '));
        String[] args = tmLine.substring(tmLine.indexOf(' ')).trim().split("\\s+");
        HashMap<String, String> parsedArgs = FeatureFunction.parseArgs(args);

        String owner = parsedArgs.get("owner");
        int span_limit = Integer.parseInt(parsedArgs.get("maxspan"));
        String path = parsedArgs.get("path");

        Grammar grammar = null;
        if (! type.equals("moses") && ! type.equals("phrase")) {
          if (new File(path).isDirectory()) {
            try {
              PackedGrammar packed_grammar = new PackedGrammar(path, span_limit, owner, type, joshuaConfiguration);
              packed_grammars.add(packed_grammar);
              grammar = packed_grammar;
            } catch (FileNotFoundException e) {
              String msg = String.format("Couldn't load packed grammar from '%s'", path)
                  + "Perhaps it doesn't exist, or it may be an old packed file format.";
              throw new RuntimeException(e);
            }
          } else {
            // thrax, hiero, samt
            grammar = new MemoryBasedBatchGrammar(type, path, owner,
                joshuaConfiguration.default_non_terminal, span_limit, joshuaConfiguration);
          }

        } else {

          int maxSourceLen = parsedArgs.containsKey("max-source-len")
              ? Integer.parseInt(parsedArgs.get("max-source-len"))
              : -1;

          joshuaConfiguration.search_algorithm = "stack";
          grammar = new PhraseTable(path, owner, type, joshuaConfiguration);
        }

        this.grammars.add(grammar);
      }

      checkSharedVocabularyChecksumsForPackedGrammars(packed_grammars);

    } else {
      LOG.warn("no grammars supplied!  Supplying dummy glue grammar.");
      MemoryBasedBatchGrammar glueGrammar = new MemoryBasedBatchGrammar("glue", joshuaConfiguration);
      glueGrammar.setSpanLimit(-1);
      glueGrammar.addGlueRules(featureFunctions);
      this.grammars.add(glueGrammar);
    }
    
    /* Add the grammar for custom entries */
    if (joshuaConfiguration.search_algorithm.equals("stack"))
      this.customPhraseTable = new PhraseTable(null, "custom", "phrase", joshuaConfiguration);
    else
      this.customPhraseTable = new MemoryBasedBatchGrammar("custom", joshuaConfiguration);
    this.grammars.add(this.customPhraseTable);
    
    /* Create an epsilon-deleting grammar */
    if (joshuaConfiguration.lattice_decoding) {
      LOG.info("Creating an epsilon-deleting grammar");
      MemoryBasedBatchGrammar latticeGrammar = new MemoryBasedBatchGrammar("lattice", joshuaConfiguration);
      latticeGrammar.setSpanLimit(-1);
      HieroFormatReader reader = new HieroFormatReader();

      String goalNT = FormatUtils.cleanNonTerminal(joshuaConfiguration.goal_symbol);
      String defaultNT = FormatUtils.cleanNonTerminal(joshuaConfiguration.default_non_terminal);

      //FIXME: too many arguments
      String ruleString = String.format("[%s] ||| [%s,1] <eps> ||| [%s,1] ||| ", goalNT, goalNT, defaultNT,
          goalNT, defaultNT);

      Rule rule = reader.parseLine(ruleString);
      latticeGrammar.addRule(rule);
      rule.estimateRuleCost(featureFunctions);

      this.grammars.add(latticeGrammar);
    }

    /* Now create a feature function for each owner */
    HashSet<String> ownersSeen = new HashSet<String>();

    for (Grammar grammar: this.grammars) {
      String owner = Vocabulary.word(grammar.getOwner());
      if (! ownersSeen.contains(owner)) {
        this.featureFunctions.add(new PhraseModel(weights, new String[] { "tm", "-owner", owner },
            joshuaConfiguration, grammar));
        ownersSeen.add(owner);
      }
    }

    LOG.info("Memory used {} MB",
        ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1000000.0));
  }

  /**
   * Checks if multiple packedGrammars have the same vocabulary by comparing their vocabulary file checksums.
   */
  private static void checkSharedVocabularyChecksumsForPackedGrammars(final List<PackedGrammar> packed_grammars) {
    String previous_checksum = "";
    for (PackedGrammar grammar : packed_grammars) {
      final String checksum = grammar.computeVocabularyChecksum();
      if (previous_checksum.isEmpty()) {
        previous_checksum = checksum;
      } else {
        if (!checksum.equals(previous_checksum)) {
          throw new RuntimeException(
              "Trying to load multiple packed grammars with different vocabularies!" +
                  "Have you packed them jointly?");
        }
        previous_checksum = checksum;
      }
    }
  }

  /*
   * This function reads the weights for the model. Feature names and their weights are listed one
   * per line in the following format:
   * 
   * FEATURE_NAME WEIGHT
   */
  private void readWeights(String fileName) {
    Decoder.weights = new FeatureVector();

    if (fileName.equals(""))
      return;

    try {
      LineReader lineReader = new LineReader(fileName);

      for (String line : lineReader) {
        line = line.replaceAll("\\s+", " ");

        if (line.equals("") || line.startsWith("#") || line.startsWith("//")
            || line.indexOf(' ') == -1)
          continue;

        String tokens[] = line.split("\\s+");
        String feature = tokens[0];
        Float value = Float.parseFloat(tokens[1]);

        // Kludge for compatibility with Moses tuners
        if (joshuaConfiguration.moses) {
          feature = demoses(feature);
        }

        weights.increment(feature, value);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
    LOG.info("Read {} weights from file '{}'", weights.size(), fileName);
  }

  private String demoses(String feature) {
    if (feature.endsWith("="))
      feature = feature.replace("=", "");
    if (feature.equals("OOV_Penalty"))
      feature = "OOVPenalty";
    else if (feature.startsWith("tm-") || feature.startsWith("lm-"))
      feature = feature.replace("-",  "_");
    return feature;
  }

  /**
   * Feature functions are instantiated with a line of the form
   *
   * <pre>
   *   FEATURE OPTIONS
   * </pre>
   *
   * Weights for features are listed separately.
   *
   * @throws IOException
   *
   */
  private void initializeFeatureFunctions() throws IOException {

    for (String featureLine : joshuaConfiguration.features) {
      // line starts with NAME, followed by args
      // 1. create new class named NAME, pass it config, weights, and the args

      String fields[] = featureLine.split("\\s+");
      String featureName = fields[0];
      
      try {
        
        Class<?> clas = getClass(featureName);
        Constructor<?> constructor = clas.getConstructor(FeatureVector.class,
            String[].class, JoshuaConfiguration.class);
        FeatureFunction feature = (FeatureFunction) constructor.newInstance(weights, fields, joshuaConfiguration);
        this.featureFunctions.add(feature);
        
      } catch (Exception e) {
        throw new RuntimeException(String.format("Unable to instantiate feature function '%s'!", featureLine), e); 
      }
    }

    for (FeatureFunction feature : featureFunctions) {
      LOG.info("FEATURE: {}", feature.logString());
    }

    weights.registerDenseFeatures(featureFunctions);
  }

  /**
   * Searches a list of predefined paths for classes, and returns the first one found. Meant for
   * instantiating feature functions.
   *
   * @param name
   * @return the class, found in one of the search paths
   * @throws ClassNotFoundException
   */
  private Class<?> getClass(String featureName) {
    Class<?> clas = null;

    String[] packages = { "org.apache.joshua.decoder.ff", "org.apache.joshua.decoder.ff.lm", "org.apache.joshua.decoder.ff.phrase" };
    for (String path : packages) {
      try {
        clas = Class.forName(String.format("%s.%s", path, featureName));
        break;
      } catch (ClassNotFoundException e) {
        try {
          clas = Class.forName(String.format("%s.%sFF", path, featureName));
          break;
        } catch (ClassNotFoundException e2) {
          // do nothing
        }
      }
    }
    return clas;
  }
}
