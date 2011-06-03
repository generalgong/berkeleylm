package edu.berkeley.nlp.lm.io;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;

import edu.berkeley.nlp.lm.ConfigOptions;
import edu.berkeley.nlp.lm.WordIndexer;
import edu.berkeley.nlp.lm.collections.Counter;
import edu.berkeley.nlp.lm.collections.Iterators;
import edu.berkeley.nlp.lm.util.Logger;
import edu.berkeley.nlp.lm.util.LongRef;
import edu.berkeley.nlp.lm.util.WorkQueue;

/**
 * Reads in n-gram count collections in the format that the Google n-grams Web1T
 * corpus comes in.
 * 
 * @author adampauls
 * 
 */
public class GoogleLmReader<W> implements LmReader<LongRef, NgramOrderedLmReaderCallback<LongRef>>
{

	private static final String START_SYMBOL = "<S>";

	private static final String END_SYMBOL = "</S>";

	private static final String UNK_SYMBOL = "<UNK>";

	private final String rootDir;

	private final WordIndexer<W> wordIndexer;

	private final ConfigOptions opts;

	public GoogleLmReader(final String rootDir, final WordIndexer<W> wordIndexer, final ConfigOptions opts) {
		this.rootDir = rootDir;

		this.opts = opts;
		this.wordIndexer = wordIndexer;

	}

	@Override
	public void parse(final NgramOrderedLmReaderCallback<LongRef> callback) {

		final List<File> listFiles = Arrays.asList(new File(rootDir).listFiles(new FilenameFilter()
		{

			@Override
			public boolean accept(final File dir, final String name) {
				return name.endsWith("gms");
			}
		}));
		Collections.sort(listFiles);
		int ngramOrder_ = 0;
		final String sortedVocabFile = "vocab_cs.gz";
		final int numGoogleLoadThreads = opts.numGoogleLoadThreads;
		for (final File ngramDir : listFiles) {
			final int ngramOrder__ = ngramOrder_;
			final File[] ngramFiles = ngramDir.listFiles(new FilenameFilter()
			{

				@Override
				public boolean accept(final File dir, final String name) {
					return ngramOrder__ == 0 ? name.equals(sortedVocabFile) : name.endsWith(".gz");
				}
			});
			if (ngramOrder_ == 0) {
				if (ngramFiles.length != 1) throw new RuntimeException("Could not find expected vocab file " + sortedVocabFile);
				final boolean manuallySortVocab = false;
				final String sortedVocabPath = ngramFiles[0].getPath();
				if (manuallySortVocab) {
					addWordsToIndexerManuallySorted(sortedVocabPath);
				} else {
					addWordToIndexer(sortedVocabPath);
				}
			}
			Logger.startTrack("Reading ngrams of order " + (ngramOrder_ + 1));
			final WorkQueue wq = new WorkQueue(numGoogleLoadThreads, true);
			for (final File ngramFile_ : ngramFiles) {
				final File ngramFile = ngramFile_;
				final int ngramOrder = ngramOrder_;
				wq.execute(new Runnable()
				{
					@Override
					public void run() {
						if (numGoogleLoadThreads == 0) Logger.startTrack("Reading ngrams from file " + ngramFile);
						try {
							int k = 0;
							for (String line : Iterators.able(IOUtils.lineIterator(ngramFile.getPath()))) {
								if (numGoogleLoadThreads == 0) if (k % 1000 == 0) Logger.logs("Line " + k);
								k++;
								line = line.trim();
								parseLine(line);
							}
						} catch (final NumberFormatException e) {
							throw new RuntimeException(e);

						} catch (final IOException e) {
							throw new RuntimeException(e);

						}
						if (numGoogleLoadThreads == 0)
							Logger.endTrack();
						else {
							Logger.logss("Finished file " + ngramFile);

						}
					}

					/**
					 * @param callback
					 * @param ngramOrder
					 * @param line
					 * @return
					 */
					private void parseLine(final String line) {
						final int tabIndex = line.indexOf('\t');

						int spaceIndex = 0;
						final int[] ngram = new int[ngramOrder + 1];
						final String words = line.substring(0, tabIndex);
						for (int i = 0;; ++i) {
							int nextIndex = line.indexOf(' ', spaceIndex);
							if (nextIndex < 0) nextIndex = words.length();
							final String word = words.substring(spaceIndex, nextIndex);
							ngram[i] = wordIndexer.getOrAddIndexFromString(word);

							if (nextIndex == words.length()) break;
							spaceIndex = nextIndex + 1;
						}
						final long count = Long.parseLong(line.substring(tabIndex + 1));
						callback.call(ngram, 0, ngram.length, new LongRef(count), words);
					}
				});
			}
			wq.finishWork();
			Logger.endTrack();
			callback.handleNgramOrderFinished(++ngramOrder_);

		}
		callback.cleanup();
		wordIndexer.setStartSymbol(wordIndexer.getWord(wordIndexer.getOrAddIndexFromString(START_SYMBOL)));
		wordIndexer.setEndSymbol(wordIndexer.getWord(wordIndexer.getOrAddIndexFromString(END_SYMBOL)));
		wordIndexer.setUnkSymbol(wordIndexer.getWord(wordIndexer.getOrAddIndexFromString(UNK_SYMBOL)));

	}

	/**
	 * @param sortedVocabPath
	 */
	private void addWordToIndexer(final String sortedVocabPath) {
		try {
			for (final String line : Iterators.able(IOUtils.lineIterator(sortedVocabPath))) {
				final String[] parts = line.split("\t");
				final String word = parts[0];
				wordIndexer.getOrAddIndexFromString(word);
			}
		} catch (final NumberFormatException e) {
			throw new RuntimeException(e);

		} catch (final IOException e) {
			throw new RuntimeException(e);

		}
	}

	/**
	 * @param sortedVocabPath
	 */
	private void addWordsToIndexerManuallySorted(final String sortedVocabPath) {
		final Counter<String> counts = new Counter<String>();
		try {
			for (final String line : Iterators.able(IOUtils.lineIterator(sortedVocabPath))) {
				final String[] parts = line.split("\t");
				final String word = parts[0];
				final long count = Long.parseLong(parts[1]);
				wordIndexer.getOrAddIndexFromString(word);
				counts.setCount(word, count);//
			}
		} catch (final NumberFormatException e) {
			throw new RuntimeException(e);

		} catch (final IOException e) {
			throw new RuntimeException(e);

		}
		for (final Entry<String, Double> entry : counts.getEntriesSortedByDecreasingCount()) {
			wordIndexer.getOrAddIndexFromString(entry.getKey());
		}
	}

}