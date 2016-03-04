package spj

import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.SVNURL
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory
import org.tmatesoft.svn.core.internal.io.svn.SVNRepositoryFactoryImpl
import org.tmatesoft.svn.core.wc.SVNClientManager
import org.tmatesoft.svn.core.wc.SVNRevision
import org.tmatesoft.svn.core.wc.SVNUpdateClient
import org.tmatesoft.svn.core.wc.SVNWCUtil
import org.tmatesoft.svn.core.*
import org.tmatesoft.svn.core.internal.io.dav.DAVRepositoryFactory
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager
import org.tmatesoft.svn.core.wc.SVNWCUtil

class RepositoryAccessService {
	// ログメッセージ出力用メソッドのオブジェクト
	spj.CreateLogMessage clm = new CreateLogMessage()

	// 設定ファイル読み込み
	String spjHome = System.getenv("SPJ_HOME")
	def config = new ConfigSlurper().parse(new File("${spjHome}/conf/config.groovy").toURI().toURL())

	// SVNのチェックアウト、アップデート結果が格納されている
	String svnOutFileDir = config.spj.svn.outFileDir

	// 計測種別マスタテーブルの「差分」を示すID
	private final CODECOUNT_TYPE_DIFF = "2"

	static{
		SVNRepositoryFactoryImpl.setup()
		DAVRepositoryFactory.setup()
		FSRepositoryFactory.setup()
	}

	/**
	 * リポジトリ存在チェック処理
	 * 計測対象URL、ユーザID、パスワードをもとにリポジトリ情報を取得する。<br>
	 * 
	 * @param user SVNアクセス用のユーザID
	 * @param passwd SVNアクセス用のパスワード
	 * @param url 計測対象URL
	 *
	 * @return 計測対象URLが存在し認証に成功：<br>
	 * 			計測対象ＵＲＬが存在する場合「最新のリビジョン番号」を返す。<br>
	 * <br>
	 * 		       計測対象URLが存在しない or 認証に失敗：<br>
	 * 			org.tmatesoft.svn.core.SVNExceptionexception発生<br>
	 *          java.net.UnknownHostException<br>
	 * 
	 * @throws Exception UnknownHostException,SVNExceptionを包括
	 * 
	 */
//	int repositoryExistenceCheck(String user, String passwd, String url) throws Exception{
	String repositoryExistenceCheck(String user, String passwd, String url) throws Exception{
		log.debug(
			clm.createStartMethodLogMessage(
				"repositoryExistenceCheck"
				, [user, passwd, url])
		)

		ISVNAuthenticationManager authManager = SVNWCUtil.createDefaultAuthenticationManager(user, passwd)
		SVNURL svnUrl = SVNURL.parseURIDecoded(url)
		SVNRepository repository = DAVRepositoryFactory.create(svnUrl)
		repository.setAuthenticationManager(authManager)

		// 最終リビジョン番号を取得する
		int latestRevision = repository.getLatestRevision().toInteger()

		log.debug(clm.createEndMethodLogMessage("repositoryExistenceCheck"))
		return latestRevision
	}

	/**
	 * 引数の計測種別、リポジトリ情報をもとに、SVNチェックアウト処理、アップデート処理を行う<br>
	 * 計測種別が「全体(1)」の場合は最新のリポジトリのみを取得し、「差分(2)」の場合は最新のリポジトリと
	 * 指定された母体リビジョン番号のリポジトリの2つを取得する。
	 *　
	 * @param user リポジトリのユーザID
	 * @param passwd リポジトリのパスワード
	 * @param url 計測対象URL
	 * @param projectId　計測対象のプロジェクトID
	 * @param codecountTargetId 計測対象のID
	 * @param codecountTypeId 計測種別のID
	 * @param baseRevisionNumber 差分計測時に使用する母体リビジョン番号
	 * 
	 */
	void measurementTargetDirExistenceCheck(
		String user
		,String passwd
		,String url
		,String projectId
		,String codecountTargetId
		,String codecountTypeId
		,String baseRevisionNumber) throws Exception {
		log.debug(
			clm.createStartMethodLogMessage(
				"measurementTargetDirExistenceCheck"
				, [user, passwd, url, projectId, codecountTargetId, codecountTypeId, baseRevisionNumber])
		)

		String measurementTargetDir = "${svnOutFileDir}/${projectId}_${codecountTargetId}"
		File measurementTargetDirFile = new File(measurementTargetDir)

		String measurementTargetDirBase ="${measurementTargetDir}_b"
		File measurementTargetDirBaseFile = new File(measurementTargetDirBase)
		
		RepositoryAccessUtil rau = new RepositoryAccessUtil()
		
		// 最新のリビジョンのソースを取得し、計測対象外ファイルは削除する
		execUpdateAndCheckout(url, measurementTargetDir, user, passwd, null)
		rau.deleteNonTargerFiles(measurementTargetDir, "1")

		// 計測種別が「差分」の場合、指定された母体リビジョン番号に相当するソースをチェックアウトし、計測対象外ファイルは削除する
		if (codecountTypeId == CODECOUNT_TYPE_DIFF) {
			execUpdateAndCheckout(url, measurementTargetDirBase, user, passwd, baseRevisionNumber?.toInteger())
			rau.deleteNonTargerFiles(measurementTargetDirBase, "1")
		}
		log.debug(clm.createEndMethodLogMessage("repositoryExistenceCheck"))
	}

	/**
	 * 引数のurlのリポジトリのソースを取得し、引数のmeasurementTargetDirに対してSVNアップデート or
	 * チェックアウトを行う。<br>
	 * すでに既存のチェックアウトファイルが存在する場合はアップデート処理を行い、存在しない場合はチェックアウトを行う。<br>
	 * ただし、既存のチェックアウトファイルが存在している場合であっても、そのチェックアウトファイルが引数のurlのリポジトリと
	 * 異なる場合は、フォルダをクリアしてチェックアウト処理を行う。
	 * 
	 * @param url リポジトリのURL
	 * @param measurementTargetDir アップデート、チェックアウト先のディレクトリ
	 * @param user リポジトリのユーザ
	 * @param passwd リポジトリのパスワード
	 * @param baseRevisionNumber 母体リビジョン番号(計測種別「全体」の場合はnull)
	 */
	private void execUpdateAndCheckout(String url, String measurementTargetDir
										, String user, String passwd
		, Integer baseRevisionNumber){
		log.debug(
			clm.createStartMethodLogMessage(
				"execUpdateAndCheckout"
				, [url,measurementTargetDir, user, passwd])
		)

		File svnEntriesFile = new File(measurementTargetDir + "/.svn/entries")		
		if (svnEntriesFile.exists() && baseRevisionNumber == null){
			// アップデート、チェックアウト先のディレクトリ配下に「～.svn/entries」が存在する場合、 同一のリポジトリかどうか確認する。
			boolean skipFlg = false
			boolean sameRepositoryFlg = false

			// .svn/entriesファイルに記載されているリポジトリ情報を検索し、同一のリポジトリの場合はsameRepositoryFlgをtrueにする
			svnEntriesFile.eachLine {
				if(!skipFlg && it.toString().matches("(.)*/(.)*")){
					// 計測対象URLの末尾に「/」がある可能性も考慮
					if(it.toString() == url
						|| it.toString() == url + "/"){
						sameRepositoryFlg = true
					}
					skipFlg = true
				}
			}

			// 同一リポジトリ(sameRepositoryFlgがtrue)の場合
			if(sameRepositoryFlg){
				// 母体リビジョン番号の指定がない(計測種別：全体)場合はアップデート処理
				if (baseRevisionNumber == null){
					update(url
						,measurementTargetDir
						,user
						,passwd)
				} else {
					// 母体リビジョン番号の指定がある(計測種別：差分)場合は、ディレクトリをクリアせずにチェックアウト処理
					// checkoutメソッドはstaticのため、ここでログ出力
					log.debug(
						clm.createStartMethodLogMessage(
							"checkout"
							, [url
								,measurementTargetDir
								,user
								,passwd
								,baseRevisionNumber])
					)
					checkout(url
						,measurementTargetDir
						,user
						,passwd
						,baseRevisionNumber)
					log.debug(clm.createEndMethodLogMessage("checkout"))
				}
			} else{
				// リポジトリが異なる場合(sameRepositoryFlgがfalse)はディレクトリをクリアしてチェックアウトを行う
				new File(measurementTargetDir).deleteDir()
				// checkoutメソッドはstaticのため、ここでログ出力
				log.debug(
					clm.createStartMethodLogMessage(
						"checkout"
						, [url
							,measurementTargetDir
							,user
							,passwd
							,baseRevisionNumber])
				)
				checkout(url
					,measurementTargetDir
					,user
					,passwd
					,baseRevisionNumber)							
				log.debug(clm.createEndMethodLogMessage("checkout"))
			}
		} else {
			// アップデート、チェックアウト先のディレクトリ配下に「～.svn/entries」が存在しない場合はフォルダをクリアしてチェックアウト処理を行う。
			new File(measurementTargetDir).deleteDir()
			// checkoutメソッドはstaticのため、ここでログ出力
			log.debug(
				clm.createStartMethodLogMessage(
					"checkout"
					, [url
						,measurementTargetDir
						,user
						,passwd
						,baseRevisionNumber])
			)
			checkout(url
				,measurementTargetDir
				,user
				,passwd
				,baseRevisionNumber)						
			log.debug(clm.createEndMethodLogMessage("checkout"))
		}
		log.debug(
			clm.createEndMethodLogMessage("execUpdateAndCheckout")
		)
	}

	/**
	 * SVNのチェックアウト、アップデート処理の認証に必要なアカウント情報のオブジェクトを生成するメソッド
	 * 
	 * @param userName
	 * @param password
	 * 
	 * @return アカウント情報のオブジェクト
	 */
	private static SVNClientManager createSVNClientManager(String userName, String password) {
		return SVNClientManager.newInstance(
			SVNWCUtil.createDefaultOptions(true)
			,userName
			,password)
	}

	/**
	 * 引数のリポジトリ情報をもとに、既存のSVNフォルダのアップデート(差分更新)を行う。
	 * 
	 * @param svnUrl
	 * @param path
	 * @param userName リポジトリのユーザID
	 * @param password リポジトリのパスワード
	 * 
	 */
	private void update(String svnUrl, String path, String userName, String password){
		log.debug(
			clm.createStartMethodLogMessage(
				"update"
				, [svnUrl, path, userName, password])
		)

		SVNUpdateClient client = createSVNClientManager(
				userName
				,password
			).getUpdateClient()

		client.doUpdate(new File(path), SVNRevision.HEAD, true, false)
		log.debug(clm.createEndMethodLogMessage("update"))
	}

	/**
	 * 引数のリポジトリ情報をもとに、最新のリビジョンでチェックアウトを行う。<br>
	 * 引数のbaseRevisionNumberを指定した場合は、対応するリビジョン番号のチェックアウトを行う。
	 * 
	 * @param svnUrl チェックアウト先のディレクトリ
	 * @param dist
	 * @param userName リポジトリのユーザID
	 * @param password リポジトリのパスワード
	 * @param baseRevisionNumber 母体リビジョン番号　※nullの場合は最新のリビジョンのソースを取得する
	 */
	private static void checkout(String svnUrl, String dist, String userName, String password, Integer baseRevisionNumber){
		SVNUpdateClient client = createSVNClientManager(
				userName
				,password
			).getUpdateClient()

		if(baseRevisionNumber == null){
			// 母体リビジョン番号がnullの場合は最新のリビジョンのソースをチェックアウト
			client.doCheckout(
				SVNURL.parseURIEncoded(svnUrl)
				,new File(dist)
				,SVNRevision.HEAD
				,SVNRevision.HEAD
				,true)
		} else{
			//母体リビジョン番号が指定されている場合は、そのリビジョンのソースをチェックアウト
			client.doCheckout(
				SVNURL.parseURIEncoded(svnUrl)
				,new File(dist)
				,SVNRevision.create(baseRevisionNumber)
				,SVNRevision.create(baseRevisionNumber)
				,true)
		}
	}
}
